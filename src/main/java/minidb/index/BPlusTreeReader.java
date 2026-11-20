package minidb.index;

import static minidb.index.BPlusTreeFormat.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

import minidb.storage.StorageException;

/** Reads one page at a time from an index file written by {@link BPlusTreeWriter}. */
public final class BPlusTreeReader implements AutoCloseable {
    private final Path file;
    private final FileChannel channel;
    private final int pageSize;
    private final int rootPage;
    private final int pageCount;
    private final IndexSource source;
    private final String name;

    private BPlusTreeReader(Path file, FileChannel channel) throws IOException {
        this.file = file;
        this.channel = channel;
        // The header page is at least MIN_PAGE_SIZE bytes, whatever the actual page size.
        ByteBuffer header = ByteBuffer.allocate(MIN_PAGE_SIZE);
        readFully(header, 0);
        if (header.getInt() != MAGIC || header.getInt() != VERSION) {
            throw new StorageException("Not a MiniDB index file: " + file);
        }
        pageSize = header.getInt();
        int columnPosition = header.getInt();
        rootPage = header.getInt();
        header.getInt(); // leafCount
        pageCount = header.getInt();
        header.getLong(); // entryCount
        source = new IndexSource(columnPosition, header.getLong(), header.getLong());
        int nameLength = header.getShort();
        if (nameLength < 1 || nameLength > MAX_NAME_LENGTH) {
            throw new StorageException("Corrupt index file: " + file);
        }
        byte[] nameBytes = new byte[nameLength];
        header.get(nameBytes);
        name = new String(nameBytes, StandardCharsets.US_ASCII);
        if (pageSize < MIN_PAGE_SIZE || pageCount < 2 || channel.size() != (long) pageSize * pageCount) {
            throw new StorageException("Corrupt index file: " + file);
        }
        checkPage(rootPage);
    }

    public static BPlusTreeReader open(Path file) {
        FileChannel channel = null;
        try {
            channel = FileChannel.open(file, StandardOpenOption.READ);
            return new BPlusTreeReader(file, channel);
        } catch (IOException | RuntimeException e) {
            StorageException error = e instanceof StorageException storage ? storage
                    : new StorageException("Cannot read index " + file, e);
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException closeError) {
                    error.addSuppressed(closeError);
                }
            }
            throw error;
        }
    }

    public IndexSource source() {
        return source;
    }

    /** The name given in CREATE INDEX. */
    public String name() {
        return name;
    }

    /** Rids of all entries whose key is in range, in key order (then rid order). */
    public long[] search(KeyRange range) {
        if (range.isEmpty()) {
            return new long[0];
        }
        try {
            // Descend toward the leftmost leaf that may hold range.min(). Taking only children
            // whose separator is strictly smaller also finds duplicates that span leaves.
            int page = rootPage;
            ByteBuffer node = readPage(page);
            while (node.getInt() == INTERNAL) {
                int keyCount = node.getInt();
                int child = node.getInt();
                for (int i = 0; i < keyCount; i++) {
                    int key = node.getInt();
                    int next = node.getInt();
                    if (key >= range.min()) {
                        break;
                    }
                    child = next;
                }
                page = child;
                node = readPage(page);
            }
            long[] rids = new long[16];
            int size = 0;
            while (true) {
                int entryCount = node.getInt();
                int nextLeaf = node.getInt();
                for (int i = 0; i < entryCount; i++) {
                    int key = node.getInt();
                    int ridCount = node.getInt();
                    if (key > range.max()) {
                        return Arrays.copyOf(rids, size);
                    }
                    if (key < range.min()) {
                        node.position(node.position() + ridCount * Long.BYTES);
                        continue;
                    }
                    if (size + ridCount > rids.length) {
                        rids = Arrays.copyOf(rids, Math.max(rids.length * 2, size + ridCount));
                    }
                    for (int k = 0; k < ridCount; k++) {
                        rids[size++] = node.getLong();
                    }
                }
                if (nextLeaf == NO_PAGE) {
                    return Arrays.copyOf(rids, size);
                }
                node = readPage(nextLeaf);
                if (node.getInt() != LEAF) {
                    throw new StorageException("Corrupt index file: " + file);
                }
            }
        } catch (IOException | RuntimeException e) {
            throw e instanceof StorageException storage ? storage
                    : new StorageException("Cannot read index " + file, e);
        }
    }

    private ByteBuffer readPage(int page) throws IOException {
        checkPage(page);
        ByteBuffer buffer = ByteBuffer.allocate(pageSize);
        readFully(buffer, (long) page * pageSize);
        return buffer;
    }

    private void checkPage(int page) {
        if (page < 1 || page >= pageCount) {
            throw new StorageException("Corrupt index file: " + file);
        }
    }

    private void readFully(ByteBuffer buffer, long position) throws IOException {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer, position + buffer.position()) < 0) {
                throw new StorageException("Truncated index file: " + file);
            }
        }
        buffer.flip();
    }

    @Override
    public void close() {
        try {
            channel.close();
        } catch (IOException e) {
            throw new StorageException("Cannot close index " + file, e);
        }
    }
}
