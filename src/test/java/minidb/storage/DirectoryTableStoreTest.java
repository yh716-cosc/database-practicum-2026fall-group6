package minidb.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import minidb.catalog.Column;
import minidb.catalog.DirectoryCatalog;
import minidb.catalog.Table;
import minidb.types.DataType;
import minidb.types.Value;

class DirectoryTableStoreTest {
    @TempDir
    Path directory;

    private static final Table STUDENTS = new Table("students", List.of(
            new Column("id", DataType.INTEGER), new Column("name", DataType.STRING)));

    private static Row row(int id, String name) {
        return new Row(List.of(new Value(DataType.INTEGER, id), new Value(DataType.STRING, name)));
    }

    private void fixture(String csv) throws Exception {
        Files.writeString(directory.resolve("students.schema"), "id INTEGER\nname STRING\n");
        Files.writeString(directory.resolve("students.csv"), csv);
    }

    @Test
    void roundTripPreservesTypesAndSpecialStrings() throws Exception {
        List<Row> rows = List.of(row(Integer.MIN_VALUE, "Smith, Alice"),
                row(Integer.MAX_VALUE, "says \"hello\""), row(0, "line 1\nline 2\r\nline 3"),
                row(-1, ""), row(3, "  Zoë 東京  "), row(4, "00123"), row(5, "NULL"));
        TableStore store = new DirectoryTableStore(directory);
        store.write(STUDENTS, rows);
        // A fresh store proves metadata and data can be recovered from disk alone.
        try (RowCursor cursor = new DirectoryTableStore(directory).scan("students")) {
            cursor.open();
            for (Row expected : rows) {
                assertEquals(expected, cursor.next());
            }
            assertNull(cursor.next());
            assertNull(cursor.next());
        }
        assertTrue(Files.readString(directory.resolve("students.csv")).contains("\"Smith, Alice\""));
        DirectoryCatalog catalog = new DirectoryCatalog(directory);
        assertEquals(STUDENTS, catalog.getTable("students"));
        assertEquals(List.of(STUDENTS), List.copyOf(catalog.tables()));
    }

    @Test
    void readsExternalCsvWithCrLfAndQuotedNewlines() throws Exception {
        fixture("id,name\r\n+12,\"Alice, A\"\r\n-2,\"hello\r\nworld\"\r\n3,\"say \"\"hi\"\"\"\r\n");
        try (RowCursor cursor = new DirectoryTableStore(directory).scan("students")) {
            cursor.open();
            assertEquals(row(12, "Alice, A"), cursor.next());
            assertEquals(row(-2, "hello\r\nworld"), cursor.next());
            assertEquals(row(3, "say \"hi\""), cursor.next());
            assertNull(cursor.next());
        }
    }

    @Test
    void emptyTableStillHasHeaderAndSchema() {
        TableStore store = new DirectoryTableStore(directory);
        store.write(STUDENTS, List.of());
        try (RowCursor cursor = store.scan("students")) {
            cursor.open();
            assertNull(cursor.next());
        }
    }

    @Test
    void cursorsAreIndependentAndSingleUse() {
        TableStore store = new DirectoryTableStore(directory);
        store.write(STUDENTS, List.of(row(1, "A"), row(2, "B")));
        try (RowCursor first = store.scan("students"); RowCursor second = store.scan("students")) {
            assertThrows(IllegalStateException.class, first::next);
            first.open();
            second.open();
            assertEquals(row(1, "A"), first.next());
            assertEquals(row(2, "B"), first.next());
            assertEquals(row(1, "A"), second.next());
            first.close();
            first.close();
            assertThrows(IllegalStateException.class, first::next);
            assertThrows(IllegalStateException.class, first::open);
            assertEquals(row(2, "B"), second.next());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "name,id\n", "id,other\n", "id,name,extra\n", "id,id\n"})
    void rejectsMissingOrIncorrectHeader(String csv) throws Exception {
        fixture(csv);
        try (RowCursor cursor = new DirectoryTableStore(directory).scan("students")) {
            assertTrue(assertThrows(StorageException.class, cursor::open).getMessage().contains("header"));
            assertThrows(IllegalStateException.class, cursor::next);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"twenty", "", "2147483648", "-2147483649", " 2", "2.0"})
    void reportsBadIntegerWithRecordAndColumn(String value) throws Exception {
        fixture("id,name\n1,A\n" + value + ",B\n");
        try (RowCursor cursor = new DirectoryTableStore(directory).scan("students")) {
            cursor.open();
            assertEquals(row(1, "A"), cursor.next());
            String message = assertThrows(StorageException.class, cursor::next).getMessage();
            assertTrue(message.contains("data record 2"));
            assertTrue(message.contains("column id"));
            assertThrows(IllegalStateException.class, cursor::next);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"1\n", "1,A,extra\n", "1,\"unterminated", "1,\"A\"junk\n"})
    void rejectsWrongWidthAndMalformedCsv(String record) throws Exception {
        fixture("id,name\n" + record);
        try (RowCursor cursor = new DirectoryTableStore(directory).scan("students")) {
            cursor.open();
            assertTrue(assertThrows(StorageException.class, cursor::next).getMessage()
                    .contains("data record 1"));
        }
    }

    @Test
    void missingFilesAndUnsafeNamesFailClearly() throws Exception {
        TableStore store = new DirectoryTableStore(directory);
        assertThrows(StorageException.class, () -> store.scan("missing"));
        assertThrows(StorageException.class, () -> store.scan("../students"));
        Files.writeString(directory.resolve("students.csv"), "id,name\n");
        assertTrue(assertThrows(StorageException.class, () -> store.scan("students"))
                .getMessage().contains("schema"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "id FLOAT\n", "id INTEGER\nid STRING\n", "id\n", "bad-name STRING\n"})
    void rejectsInvalidSchema(String schema) throws Exception {
        fixture("id,name\n");
        Files.writeString(directory.resolve("students.schema"), schema);
        assertThrows(StorageException.class, () -> new DirectoryTableStore(directory).scan("students"));
    }

    @Test
    void failedWriteCleansStagingFilesAndAllowsRetry() throws Exception {
        TableStore store = new DirectoryTableStore(directory);
        Row wrongType = new Row(List.of(new Value(DataType.INTEGER, "2"), new Value(DataType.STRING, "B")));
        String message = assertThrows(StorageException.class,
                () -> store.write(STUDENTS, List.of(row(1, "A"), wrongType))).getMessage();
        assertTrue(message.contains("data record 2, column id"));
        try (var files = Files.list(directory)) {
            assertEquals(0, files.count());
        }
        store.write(STUDENTS, List.of(row(1, "A")));
        assertEquals(STUDENTS, new DirectoryCatalog(directory).getTable("students"));
    }

    @Test
    void sourceFailureDoesNotPublishPartialTable() throws Exception {
        Iterable<Row> broken = () -> new Iterator<>() {
            int count;
            public boolean hasNext() { return true; }
            public Row next() {
                if (count++ == 0) { return row(1, "A"); }
                throw new IllegalStateException("source failed");
            }
        };
        assertThrows(StorageException.class, () -> new DirectoryTableStore(directory).write(STUDENTS, broken));
        try (var files = Files.list(directory)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void refusesOverwriteEvenWhenOnlyOneFileExists() throws Exception {
        TableStore store = new DirectoryTableStore(directory);
        store.write(STUDENTS, List.of(row(1, "original")));
        String csv = Files.readString(directory.resolve("students.csv"));
        assertThrows(StorageException.class, () -> store.write(STUDENTS, List.of(row(2, "new"))));
        assertEquals(csv, Files.readString(directory.resolve("students.csv")));
        Files.delete(directory.resolve("students.schema"));
        assertThrows(StorageException.class, () -> store.write(STUDENTS, List.of()));
        Files.delete(directory.resolve("students.csv"));
        Files.writeString(directory.resolve("students.schema"), "id INTEGER\n");
        assertThrows(StorageException.class, () -> store.write(STUDENTS, List.of()));
    }

    @Test
    void rejectsNullCellsWrongWidthsAndDeclaredTypes() {
        TableStore store = new DirectoryTableStore(directory);
        for (Row invalid : List.of(new Row(List.of()),
                new Row(List.of(new Value(DataType.INTEGER, 1), new Value(DataType.STRING, null))),
                new Row(List.of(new Value(DataType.STRING, "1"), new Value(DataType.STRING, "A"))))) {
            assertThrows(StorageException.class, () -> store.write(STUDENTS, List.of(invalid)));
        }
    }

    @Test
    void streamsFileLargerThanHeapInBothDirections() throws Exception {
        Path log = directory.resolve("probe.log");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx32m", "-cp", System.getProperty("java.class.path"), HeapProbe.class.getName(),
                directory.resolve("large").toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Streaming probe timed out");
            assertEquals(0, process.exitValue(), Files.readString(log));
            assertTrue(Files.size(directory.resolve("large/students.csv")) > 100_000_000L);
        } finally {
            process.destroyForcibly();
        }
    }

    public static final class HeapProbe {
        public static void main(String[] args) {
            String payload = "x".repeat(1024);
            int count = 120_000;
            TableStore store = new DirectoryTableStore(Path.of(args[0]));
            store.write(STUDENTS, () -> IntStream.range(0, count).mapToObj(i -> row(i, payload)).iterator());
            int seen = 0;
            try (RowCursor cursor = store.scan("students")) {
                cursor.open();
                Row row;
                while ((row = cursor.next()) != null) {
                    if (!row.equals(row(seen, payload))) {
                        throw new AssertionError("Incorrect row " + seen);
                    }
                    seen++;
                }
            }
            if (seen != count) {
                throw new AssertionError("Expected " + count + " rows, found " + seen);
            }
        }
    }
}
