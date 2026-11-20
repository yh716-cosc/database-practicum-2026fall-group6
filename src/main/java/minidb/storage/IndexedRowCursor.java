package minidb.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

import minidb.catalog.Table;
import minidb.catalog.TableDefinition.Format;
import minidb.index.BPlusTreeReader;
import minidb.index.KeyRange;

/**
 * A single-use index scan. Looks up matching rids, then seeks to each record in the CSV.
 * Rids are sorted by byte offset, so rows come back in file order, exactly as a full scan
 * would return them, and the file is read front to back. Memory holds one long per match
 * plus a byte window of the CSV; nearby matches share a window, so they cost no extra reads.
 * Records are split straight from the window bytes by {@link CsvRows#parseRecord}, without
 * building a CSV parser per row.
 */
final class IndexedRowCursor implements RowCursor {
    private enum State { NEW, OPEN, EOF, CLOSED }

    private static final int WINDOW_BYTES = 8192;

    private final Path csv;
    private final Path index;
    private final Table table;
    private final Format format;
    private final int columnPosition;
    private final KeyRange range;
    private State state = State.NEW;
    private long[] rids;
    private int nextRid;
    private FileChannel channel;
    private ByteBuffer window = ByteBuffer.allocate(WINDOW_BYTES);
    private long windowStart;
    private int windowLength;
    private boolean windowReachesEof;

    IndexedRowCursor(Path csv, Path index, Table table, Format format, int columnPosition,
            KeyRange range) {
        this.csv = csv;
        this.index = index;
        this.table = table;
        this.format = format;
        this.columnPosition = columnPosition;
        this.range = range;
    }

    @Override
    public void open() {
        if (state != State.NEW) {
            throw new IllegalStateException("A cursor can only be opened once; request a new scan");
        }
        try {
            // Queries never build indexes. The planner only chooses fresh ones, so this
            // fails only if the CSV changed after planning.
            if (!CsvIndexes.isFresh(csv, index, columnPosition)) {
                throw new StorageException("Index " + index + " is out of date; run CREATE INDEX again");
            }
            try (BPlusTreeReader reader = BPlusTreeReader.open(index)) {
                rids = reader.search(range);
            }
            Arrays.sort(rids);
            channel = FileChannel.open(csv, StandardOpenOption.READ);
            state = State.OPEN;
        } catch (IOException | RuntimeException e) {
            throw fail("Cannot open index scan of " + csv, e);
        }
    }

    @Override
    public Row next() {
        if (state == State.EOF) {
            return null;
        }
        if (state != State.OPEN) {
            throw new IllegalStateException("Cursor must be open before reading");
        }
        if (nextRid == rids.length) {
            close();
            state = State.EOF;
            return null;
        }
        long rid = rids[nextRid];
        String context = csv + ", byte offset " + rid;
        try {
            Row row = CsvRows.toRow(readRecord(rid, context).fields(), table, format, context);
            nextRid++;
            return row;
        } catch (IOException | RuntimeException e) {
            throw fail(context + ": cannot read CSV", e);
        }
    }

    /** Parses the record at rid, reading the CSV only if the window doesn't hold all of it. */
    private CsvRows.ParsedRecord readRecord(long rid, String context) throws IOException {
        if (rid < windowStart || rid >= windowStart + windowLength) {
            fill(rid, window.capacity());
        }
        while (true) {
            int start = (int) (rid - windowStart);
            if (start >= windowLength && windowReachesEof) {
                throw new StorageException(context + ": index points past the last record");
            }
            CsvRows.ParsedRecord record = CsvRows.parseRecord(window.array(), start, windowLength,
                    windowReachesEof);
            if (record != null) {
                return record;
            }
            // The record runs past the window: reload from its start, doubling the window
            // when the record alone is bigger than it.
            fill(rid, start == 0 ? window.capacity() * 2 : window.capacity());
        }
    }

    private void fill(long position, int capacity) throws IOException {
        if (capacity > window.capacity()) {
            window = ByteBuffer.allocate(capacity);
        }
        window.clear();
        windowReachesEof = false;
        long at = position;
        while (window.hasRemaining()) {
            int read = channel.read(window, at);
            if (read < 0) {
                windowReachesEof = true;
                break;
            }
            at += read;
        }
        windowStart = position;
        windowLength = window.position();
    }

    private StorageException fail(String message, Exception cause) {
        StorageException error = cause instanceof StorageException storage ? storage
                : new StorageException(message, cause);
        try {
            close();
        } catch (RuntimeException closeError) {
            error.addSuppressed(closeError);
        }
        return error;
    }

    @Override
    public void close() {
        if (state == State.CLOSED) {
            return;
        }
        state = State.CLOSED;
        try {
            if (channel != null) {
                channel.close();
            }
        } catch (IOException e) {
            throw new StorageException("Cannot close CSV " + csv, e);
        }
    }
}
