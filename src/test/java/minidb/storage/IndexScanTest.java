package minidb.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import minidb.catalog.DirectoryCatalog;
import minidb.index.BPlusTreeReader;
import minidb.index.KeyRange;

class IndexScanTest {
    @TempDir Path data;
    private DirectoryCatalog catalog;
    private DirectoryTableStore store;

    @BeforeEach void prepare() throws Exception {
        Files.writeString(data.resolve("people.schema"), "id INTEGER\nname STRING\nage INTEGER\n");
        // Quoted commas, embedded newlines, escaped quotes, and multi-byte UTF-8 all shift
        // byte offsets away from line or character counts.
        write("id,name,age\n"
                + "1,\"Smith, Bob\",20\n"
                + "2,\"multi\nline\",17\n"
                + "3,\"Zoë 😀 \"\"Z\"\"\",20\n"
                + "4,plain,30\n"
                + "5,\"\",20\n");
        catalog = new DirectoryCatalog(data);
        store = new DirectoryTableStore(data, catalog);
    }

    private void write(String csv) throws Exception {
        Files.writeString(data.resolve("people.csv"), csv, StandardCharsets.UTF_8);
    }

    /** Moves the modification time forward so even a same-size rewrite counts as a change. */
    private void touch() throws Exception {
        Path csv = data.resolve("people.csv");
        Files.setLastModifiedTime(csv, FileTime.fromMillis(Files.getLastModifiedTime(csv).toMillis() + 5000));
    }

    private static List<List<Object>> drain(RowCursor cursor) {
        List<List<Object>> rows = new ArrayList<>();
        try (cursor) {
            cursor.open();
            Row row;
            while ((row = cursor.next()) != null) {
                rows.add(row.values().stream().map(value -> value.raw()).toList());
            }
        }
        return rows;
    }

    @Test void createIndexBuildsTheFileAndRecordsItsName() {
        assertEquals(List.of(), catalog.indexedColumns("people"));
        assertFalse(store.createIndex("idx_age", "people", "age"));
        assertEquals(List.of("age"), catalog.indexedColumns("people"));
        try (BPlusTreeReader reader = BPlusTreeReader.open(data.resolve("people.age.idx"))) {
            assertEquals("idx_age", reader.name());
        }
        assertTrue(store.createIndex("idx_age", "people", "age"));
        assertThrows(StorageException.class, () -> store.createIndex("age_again", "people", "age"));
        store.dropIndex("idx_age");
        assertEquals(List.of(), catalog.indexedColumns("people"));
        assertFalse(store.createIndex("age_again", "people", "age"));
        try (BPlusTreeReader reader = BPlusTreeReader.open(data.resolve("people.age.idx"))) {
            assertEquals("age_again", reader.name());
        }
    }

    @Test void seeksToQuotedMultilineAndUnicodeRecordsInFileOrder() {
        store.createIndex("idx_age", "people", "age");
        store.createIndex("idx_id", "people", "id");
        assertEquals(List.of(List.of(1, "Smith, Bob", 20), List.of(3, "Zoë 😀 \"Z\"", 20), List.of(5, "", 20)),
                drain(store.indexScan("people", "age", KeyRange.equalTo(20))));
        assertEquals(List.of(List.of(2, "multi\nline", 17)),
                drain(store.indexScan("people", "age", new KeyRange(Integer.MIN_VALUE, 19))));
        assertEquals(drain(store.scan("people")), drain(store.indexScan("people", "id", KeyRange.all())));
        assertEquals(List.of(), drain(store.indexScan("people", "id", KeyRange.equalTo(99))));
    }

    @Test void readsRecordsThatCrossOrExceedTheReadWindow() throws Exception {
        // Records of varied length straddle the cursor's 8 KB window; some are larger than it,
        // with quoted newlines and multi-byte characters, and the file has CRLF endings and
        // no final line break.
        StringBuilder csv = new StringBuilder("id,name,age\r\n");
        for (int id = 1; id <= 300; id++) {
            String name = id % 50 == 0 ? "\"big\n" + "é".repeat(6000) + "\"" : "n" + "x".repeat(id % 97);
            csv.append(id).append(',').append(name).append(',').append(id % 3);
            if (id < 300) {
                csv.append("\r\n");
            }
        }
        write(csv.toString());
        store.createIndex("idx_age", "people", "age");
        store.createIndex("idx_id", "people", "id");
        List<List<Object>> all = drain(store.scan("people"));
        assertEquals(300, all.size());
        assertEquals(all, drain(store.indexScan("people", "id", KeyRange.all())));
        assertEquals(all.stream().filter(row -> row.get(2).equals(1)).toList(),
                drain(store.indexScan("people", "age", KeyRange.equalTo(1))));
        assertEquals(List.of(all.get(299)), drain(store.indexScan("people", "id", KeyRange.equalTo(300))));
    }

    @Test void indexScansNeverBuildIndexes() {
        assertThrows(StorageException.class, () -> store.indexScan("people", "age", KeyRange.all()));
        assertFalse(Files.exists(data.resolve("people.age.idx")));
    }

    @Test void outOfDateIndexesAreIgnoredUntilRebuilt() throws Exception {
        store.createIndex("idx_age", "people", "age");
        RowCursor plannedBeforeChange = store.indexScan("people", "age", KeyRange.equalTo(20));
        write("id,name,age\n9,new,20\n");
        touch();
        assertEquals(List.of(), catalog.indexedColumns("people"));
        assertThrows(StorageException.class, () -> store.indexScan("people", "age", KeyRange.all()));
        StorageException error = assertThrows(StorageException.class, plannedBeforeChange::open);
        assertTrue(error.getMessage().contains("CREATE INDEX"), error.getMessage());

        assertTrue(store.createIndex("idx_age", "people", "age"));
        assertEquals(List.of(List.of(9, "new", 20)), drain(store.indexScan("people", "age", KeyRange.equalTo(20))));
    }

    @Test void unreadableIndexFilesAreIgnored() throws Exception {
        store.createIndex("idx_age", "people", "age");
        Files.write(data.resolve("people.age.idx"), new byte[10]);
        assertEquals(List.of(), catalog.indexedColumns("people"));
        assertTrue(store.createIndex("idx_age", "people", "age"));
        assertEquals(List.of("age"), catalog.indexedColumns("people"));
    }

    @Test void malformedRecordsFailTheBuildAndLeaveNoFile() throws Exception {
        write("id,name,age\n1,a,20\nbad,b,30\n");
        StorageException error = assertThrows(StorageException.class,
                () -> store.createIndex("idx_age", "people", "age"));
        assertTrue(error.getMessage().contains("data record 2"), error.getMessage());
        assertFalse(Files.exists(data.resolve("people.age.idx")));
        try (var files = Files.list(data)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test void rejectsBadIndexTargetsAndNames() {
        assertThrows(StorageException.class, () -> store.createIndex("i", "people", "missing"));
        assertThrows(StorageException.class, () -> store.createIndex("i", "people", "name"));
        assertThrows(StorageException.class, () -> store.createIndex("i", "nobody", "age"));
        assertThrows(StorageException.class, () -> store.createIndex("bad-name", "people", "age"));
        assertThrows(StorageException.class, () -> store.createIndex("x".repeat(129), "people", "age"));
        try (var files = assertDoesNotThrow(() -> Files.list(data))) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".idx")));
        }
    }

    @Test void indexFilesDoNotAppearAsTables() {
        store.createIndex("idx_age", "people", "age");
        assertEquals(List.of("people"), catalog.tables().stream().map(table -> table.name()).toList());
    }
}
