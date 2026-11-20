package minidb.index;

import static minidb.index.BPlusTreeFormat.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import minidb.storage.StorageException;

/** Bulk-loads an unclustered B+ tree bottom-up from (key, rid) entries. */
public final class BPlusTreeWriter {
    public record Entry(int key, long rid) {}

    public static final int MAX_NAME_LENGTH = BPlusTreeFormat.MAX_NAME_LENGTH;

    private BPlusTreeWriter() {
    }

    /** Writes a complete index file. Entries may be in any order; the input is not modified. */
    public static void write(Path file, String name, List<Entry> entries, IndexSource source) {
        write(file, name, entries, source, DEFAULT_PAGE_SIZE);
    }

    /** Small page sizes let tests build multi-level trees from few entries. */
    static void write(Path file, String name, List<Entry> entries, IndexSource source, int pageSize) {
        if (pageSize < MIN_PAGE_SIZE) {
            throw new IllegalArgumentException("Page size must be at least " + MIN_PAGE_SIZE);
        }
        if (name == null || name.isEmpty() || name.length() > MAX_NAME_LENGTH
                || !StandardCharsets.US_ASCII.newEncoder().canEncode(name)) {
            throw new IllegalArgumentException("Index name must be 1-" + MAX_NAME_LENGTH + " ASCII characters");
        }
        List<Entry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparingInt(Entry::key).thenComparingLong(Entry::rid));

        List<ByteBuffer> pages = new ArrayList<>();
        pages.add(null); // Header, filled in once the root page is known.
        List<Integer> children = new ArrayList<>();
        List<Integer> firstKeys = new ArrayList<>();
        writeLeaves(sorted, pageSize, pages, children, firstKeys);
        int leafCount = children.size();

        // Each internal level indexes the first key of every node in the level below.
        int fanout = (pageSize - NODE_HEADER_SIZE) / (2 * Integer.BYTES) + 1;
        while (children.size() > 1) {
            List<Integer> parents = new ArrayList<>();
            List<Integer> parentKeys = new ArrayList<>();
            for (int start = 0; start < children.size(); start += fanout) {
                int end = Math.min(start + fanout, children.size());
                ByteBuffer node = ByteBuffer.allocate(pageSize);
                node.putInt(INTERNAL).putInt(end - start - 1).putInt(children.get(start));
                for (int i = start + 1; i < end; i++) {
                    node.putInt(firstKeys.get(i)).putInt(children.get(i));
                }
                parents.add(pages.size());
                parentKeys.add(firstKeys.get(start));
                pages.add(node);
            }
            children = parents;
            firstKeys = parentKeys;
        }

        ByteBuffer header = ByteBuffer.allocate(pageSize);
        header.putInt(MAGIC).putInt(VERSION).putInt(pageSize).putInt(source.columnPosition())
                .putInt(children.get(0)).putInt(leafCount).putInt(pages.size())
                .putLong(sorted.size()).putLong(source.csvSize()).putLong(source.csvModifiedNanos())
                .putShort((short) name.length()).put(name.getBytes(StandardCharsets.US_ASCII));
        pages.set(0, header);

        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (ByteBuffer page : pages) {
                page.clear();
                while (page.hasRemaining()) {
                    channel.write(page);
                }
            }
        } catch (IOException e) {
            throw new StorageException("Cannot write index " + file, e);
        }
    }

    private static void writeLeaves(List<Entry> sorted, int pageSize, List<ByteBuffer> pages,
            List<Integer> leaves, List<Integer> firstKeys) {
        ByteBuffer leaf = newLeaf(pageSize);
        int entryCount = 0;
        int i = 0;
        while (i < sorted.size()) {
            int key = sorted.get(i).key();
            int end = i;
            while (end < sorted.size() && sorted.get(end).key() == key) {
                end++;
            }
            while (i < end) {
                int room = (leaf.remaining() - 2 * Integer.BYTES) / Long.BYTES;
                if (room < 1) {
                    finishLeaf(leaf, entryCount, pages, leaves);
                    leaf = newLeaf(pageSize);
                    entryCount = 0;
                    continue;
                }
                if (entryCount == 0) {
                    firstKeys.add(key);
                }
                int take = Math.min(room, end - i);
                leaf.putInt(key).putInt(take);
                for (int k = i; k < i + take; k++) {
                    leaf.putLong(sorted.get(k).rid());
                }
                entryCount++;
                i += take;
            }
        }
        if (entryCount > 0 || leaves.isEmpty()) {
            if (entryCount == 0) {
                firstKeys.add(Integer.MIN_VALUE); // Empty table: one empty leaf is the root.
            }
            finishLeaf(leaf, entryCount, pages, leaves);
        }
        // Leaves are consecutive pages; only the last one has no successor.
        pages.get(leaves.get(leaves.size() - 1)).putInt(2 * Integer.BYTES, NO_PAGE);
    }

    private static ByteBuffer newLeaf(int pageSize) {
        ByteBuffer leaf = ByteBuffer.allocate(pageSize);
        leaf.putInt(LEAF).putInt(0).putInt(NO_PAGE);
        return leaf;
    }

    private static void finishLeaf(ByteBuffer leaf, int entryCount, List<ByteBuffer> pages,
            List<Integer> leaves) {
        int page = pages.size();
        leaf.putInt(Integer.BYTES, entryCount).putInt(2 * Integer.BYTES, page + 1);
        leaves.add(page);
        pages.add(leaf);
    }
}
