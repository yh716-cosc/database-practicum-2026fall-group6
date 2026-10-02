package minidb.exec;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import minidb.catalog.Column;
import minidb.catalog.Table;
import minidb.storage.DirectoryTableStore;
import minidb.storage.Row;
import minidb.storage.RowCursor;
import minidb.storage.StorageException;
import minidb.storage.TableStore;
import minidb.types.DataType;
import minidb.types.Value;

class TableScanOperatorTest {
    @TempDir Path directory;

    private static Row row(int id, String name) {
        return new Row(List.of(new Value(DataType.INTEGER, id), new Value(DataType.STRING, name)));
    }

    private static final class Cursor implements RowCursor {
        final List<Row> rows;
        int opens, reads, closes;
        RuntimeException openFailure, readFailure, closeFailure;

        Cursor(Row... rows) { this.rows = List.of(rows); }

        public void open() {
            opens++;
            if (openFailure != null) { throw openFailure; }
        }

        public Row next() {
            int index = reads++;
            if (readFailure != null) { throw readFailure; }
            return index < rows.size() ? rows.get(index) : null;
        }

        public void close() {
            closes++;
            if (closeFailure != null) { throw closeFailure; }
        }
    }

    private static final class Store implements TableStore {
        final Cursor cursor;
        int scans;
        String requestedTable;
        RuntimeException scanFailure;

        Store(Cursor cursor) { this.cursor = cursor; }

        public RowCursor scan(String tableName) {
            scans++;
            requestedTable = tableName;
            if (scanFailure != null) { throw scanFailure; }
            return cursor;
        }

        public void write(Table table, Iterable<Row> rows) {
            fail("A scan must not write tables");
        }
    }

    @Test
    void readsOnDemandAndPreservesRowsOrderAndDuplicates() {
        Row first = row(1, "Alice");
        Row last = row(2, "");
        Cursor cursor = new Cursor(first, first, last);
        Store store = new Store(cursor);
        TableScanOperator scan = new TableScanOperator(store, "students");
        assertEquals(0, store.scans);
        scan.open();
        assertEquals("students", store.requestedTable);
        assertEquals(1, cursor.opens);
        assertEquals(0, cursor.reads);
        assertSame(first, scan.next());
        assertEquals(1, cursor.reads);
        assertSame(first, scan.next());
        assertSame(last, scan.next());
        assertNull(scan.next());
        assertEquals(1, cursor.closes);
        assertNull(scan.next());
        assertEquals(4, cursor.reads);
        scan.close();
        scan.close();
        assertEquals(1, cursor.closes);
        assertThrows(IllegalStateException.class, scan::next);
        assertThrows(IllegalStateException.class, scan::open);
    }

    @Test
    void emptyScanClosesAtEofAndCannotReopen() {
        Cursor cursor = new Cursor();
        TableScanOperator scan = new TableScanOperator(new Store(cursor), "empty");
        scan.open();
        assertNull(scan.next());
        assertNull(scan.next());
        assertEquals(1, cursor.reads);
        assertEquals(1, cursor.closes);
        assertThrows(IllegalStateException.class, scan::open);
    }

    @Test
    void enforcesLifecycleAndSupportsEarlyClose() {
        Cursor cursor = new Cursor(row(1, "A"), row(2, "B"));
        Store store = new Store(cursor);
        TableScanOperator scan = new TableScanOperator(store, "students");
        assertThrows(IllegalStateException.class, scan::next);
        scan.open();
        assertThrows(IllegalStateException.class, scan::open);
        assertEquals(1, store.scans);
        assertEquals(row(1, "A"), scan.next());
        scan.close();
        scan.close();
        assertEquals(1, cursor.closes);
        assertEquals(1, cursor.reads);
        assertThrows(IllegalStateException.class, scan::next);
    }

    @Test
    void closingBeforeOpenDoesNotAcquireACursor() {
        Store store = new Store(new Cursor());
        TableScanOperator scan = new TableScanOperator(store, "students");
        scan.close();
        scan.close();
        assertEquals(0, store.scans);
        assertThrows(IllegalStateException.class, scan::open);
        assertThrows(IllegalStateException.class, scan::next);
    }

    @Test
    void scanAcquisitionFailureIsPreserved() {
        Store store = new Store(new Cursor());
        store.scanFailure = new StorageException("Missing table");
        TableScanOperator scan = new TableScanOperator(store, "missing");
        assertSame(store.scanFailure, assertThrows(StorageException.class, scan::open));
        assertEquals(0, store.cursor.closes);
        scan.close();
        assertThrows(IllegalStateException.class, scan::open);
        assertThrows(IllegalStateException.class, scan::next);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void openAndReadFailuresCloseCursorAndPreserveSuppressedErrors(boolean duringOpen) {
        Cursor cursor = new Cursor();
        RuntimeException primary = new StorageException("Primary failure");
        cursor.closeFailure = new StorageException("Cleanup failure");
        TableScanOperator scan = new TableScanOperator(new Store(cursor), "students");
        RuntimeException actual;
        if (duringOpen) {
            cursor.openFailure = primary;
            actual = assertThrows(StorageException.class, scan::open);
        } else {
            scan.open();
            cursor.readFailure = primary;
            actual = assertThrows(StorageException.class, scan::next);
        }
        assertSame(primary, actual);
        assertArrayEquals(new Throwable[] {cursor.closeFailure}, actual.getSuppressed());
        scan.close();
        assertEquals(1, cursor.closes);
        assertThrows(IllegalStateException.class, scan::open);
        assertThrows(IllegalStateException.class, scan::next);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void closeFailureAtEofOrExplicitCloseIsReportedOnlyOnce(boolean atEof) {
        Cursor cursor = new Cursor();
        cursor.closeFailure = new StorageException("Close failed");
        TableScanOperator scan = new TableScanOperator(new Store(cursor), "students");
        scan.open();
        RuntimeException actual = atEof
                ? assertThrows(StorageException.class, scan::next)
                : assertThrows(StorageException.class, scan::close);
        assertSame(cursor.closeFailure, actual);
        scan.close();
        assertEquals(1, cursor.closes);
        assertThrows(IllegalStateException.class, scan::next);
    }

    @Test
    void readsRealCsvWithIndependentScans() {
        TableStore store = new DirectoryTableStore(directory);
        Table table = new Table("students", List.of(new Column("id", DataType.INTEGER),
                new Column("name", DataType.STRING)));
        List<Row> rows = List.of(row(Integer.MIN_VALUE, "Smith, Alice"),
                row(Integer.MAX_VALUE, "line 1\nline 2"), row(0, ""));
        store.write(table, rows);
        TableScanOperator first = new TableScanOperator(store, "students");
        TableScanOperator second = new TableScanOperator(store, "students");
        try {
            first.open();
            second.open();
            for (Row expected : rows) { assertEquals(expected, first.next()); }
            assertNull(first.next());
            assertEquals(rows.get(0), second.next());
        } finally {
            first.close();
            second.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong,name\n", "id,name\nbad,Alice\n"})
    void propagatesRealStorageFailures(String csv) throws Exception {
        Files.writeString(directory.resolve("students.schema"), "id INTEGER\nname STRING\n");
        Path data = directory.resolve("students.csv");
        Files.writeString(data, csv);
        TableScanOperator scan = new TableScanOperator(new DirectoryTableStore(directory), "students");
        if (csv.startsWith("wrong")) {
            assertTrue(assertThrows(StorageException.class, scan::open).getMessage().contains("header"));
        } else {
            scan.open();
            assertTrue(assertThrows(StorageException.class, scan::next).getMessage().contains("column id"));
        }
        scan.close();
        assertThrows(IllegalStateException.class, scan::next);
        Files.delete(data);
    }
}
