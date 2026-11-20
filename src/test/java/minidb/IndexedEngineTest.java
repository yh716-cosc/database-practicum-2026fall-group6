package minidb;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import minidb.exec.QueryResult;
import minidb.storage.StorageException;

/** Every query must return the same rows, in the same order, with and without indexes. */
class IndexedEngineTest {
    @TempDir Path indexed;
    @TempDir Path plain;

    @BeforeEach void prepare() throws Exception {
        Random random = new Random(6);
        StringBuilder csv = new StringBuilder("id,name,age,score\n");
        for (int i = 0; i < 2000; i++) {
            csv.append(i).append(",\"n").append(random.nextInt(50)).append(", x\",")
                    .append(random.nextInt(60)).append(',').append(random.nextInt(101) - 50).append('\n');
        }
        for (Path dir : List.of(indexed, plain)) {
            Files.writeString(dir.resolve("t.schema"), "id INTEGER\nname STRING\nage INTEGER\nscore INTEGER\n");
            Files.writeString(dir.resolve("t.csv"), csv.toString());
            Files.writeString(dir.resolve("u.schema"), "uid INTEGER\nage INTEGER\n");
            Files.writeString(dir.resolve("u.csv"), "uid,age\n1,10\n2,20\n3,30\n");
        }
    }

    private void createIndexes() {
        Engine engine = new Engine(indexed);
        for (String column : List.of("age", "score", "id")) {
            engine.execute("CREATE INDEX idx_" + column + " ON t (" + column + ")");
        }
    }

    private static List<List<Object>> rows(QueryResult result) {
        return result.rows().stream().map(row -> row.values().stream().map(v -> v.raw()).toList()).toList();
    }

    private void assertSameResults(String sql) {
        QueryResult expected = new Engine(plain).execute(sql);
        QueryResult actual = new Engine(indexed).execute(sql);
        assertEquals(expected.columnNames(), actual.columnNames(), sql);
        assertEquals(rows(expected), rows(actual), sql);
    }

    private long indexFiles(Path dir) throws Exception {
        try (var files = Files.list(dir)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".idx")).count();
        }
    }

    @Test void createIndexReportsWhatItDid() throws Exception {
        Engine engine = new Engine(indexed);
        QueryResult created = engine.execute("CREATE INDEX idx_age ON t (age);");
        assertEquals("Created index idx_age on t(age)", created.message());
        assertTrue(created.rows().isEmpty());
        assertTrue(Files.exists(indexed.resolve("t.age.idx")));
        assertEquals("Rebuilt index idx_age on t(age)", engine.execute("create index idx_age on t(age)").message());
        assertNull(engine.execute("SELECT id FROM t WHERE age = 1").message());
    }

    @Test void createIndexRejectsNameOrColumnConflicts() throws Exception {
        Engine engine = new Engine(indexed);
        engine.execute("CREATE INDEX idx_age ON t (age)");
        byte[] before = Files.readAllBytes(indexed.resolve("t.age.idx"));
        assertTrue(assertThrows(StorageException.class,
                () -> engine.execute("CREATE INDEX by_age ON t (age)")).getMessage().contains("already indexed by idx_age"));
        assertTrue(assertThrows(StorageException.class,
                () -> engine.execute("CREATE INDEX idx_age ON u (age)")).getMessage().contains("already exists on t.age"));
        assertArrayEquals(before, Files.readAllBytes(indexed.resolve("t.age.idx")));
        assertEquals(1, indexFiles(indexed));
    }

    @Test void dropIndexDeletesTheNamedFileAndQueriesFallBackToScans() throws Exception {
        createIndexes();
        Engine engine = new Engine(indexed);
        QueryResult dropped = engine.execute("DROP INDEX idx_age;");
        assertEquals("Dropped index idx_age", dropped.message());
        assertTrue(dropped.rows().isEmpty());
        assertFalse(Files.exists(indexed.resolve("t.age.idx")));
        assertTrue(Files.exists(indexed.resolve("t.score.idx")));
        assertSameResults("SELECT * FROM t WHERE age = 7");

        assertThrows(StorageException.class, () -> engine.execute("DROP INDEX idx_age"));
        assertThrows(StorageException.class, () -> engine.execute("DROP INDEX nobody"));
        assertEquals("Created index idx_age on u(age)", engine.execute("CREATE INDEX idx_age ON u (age)").message());
        assertEquals("Dropped index idx_age", engine.execute("drop index idx_age").message());
        assertEquals(2, indexFiles(indexed));
    }

    @Test void createIndexErrorsLeaveNoFiles() throws Exception {
        Engine engine = new Engine(indexed);
        for (String sql : List.of("CREATE INDEX i ON t (missing)", "CREATE INDEX i ON t (name)",
                "CREATE INDEX i ON nobody (age)")) {
            assertThrows(StorageException.class, () -> engine.execute(sql), sql);
        }
        assertEquals(0, indexFiles(indexed));
    }

    @Test void selectsNeverCreateIndexFiles() throws Exception {
        Engine engine = new Engine(indexed);
        engine.execute("SELECT * FROM t WHERE age = 7");
        engine.execute("SELECT * FROM t WHERE id >= 10 AND score < 0");
        assertEquals(0, indexFiles(indexed));
    }

    @Test void randomPredicatesMatchFullScans() {
        createIndexes();
        Random random = new Random(42);
        String[] columns = {"age", "score", "id"};
        String[] ops = {"=", "<", "<=", ">", ">=", "<>"};
        for (int i = 0; i < 150; i++) {
            String a = columns[random.nextInt(3)] + " " + ops[random.nextInt(6)] + " " + (random.nextInt(140) - 70);
            String b = columns[random.nextInt(3)] + " " + ops[random.nextInt(6)] + " " + (random.nextInt(140) - 70);
            String joiner = random.nextInt(4) == 0 ? " OR " : " AND ";
            assertSameResults("SELECT * FROM t WHERE " + a + joiner + b);
        }
    }

    @Test void indexScansComposeWithOtherClauses() {
        createIndexes();
        assertFalse(rows(new Engine(indexed).execute("SELECT * FROM t WHERE age = 7")).isEmpty());
        for (String sql : List.of(
                "SELECT name, id FROM t WHERE age = 7 ORDER BY name DESC, id LIMIT 5",
                "SELECT id FROM t WHERE 10 <= age AND age < 12 AND name = 'n3, x' LIMIT 3",
                "SELECT id FROM t WHERE age > 2147483647",
                "SELECT id FROM t WHERE age < -2147483648",
                "SELECT * FROM t JOIN u ON t.age = u.age WHERE t.age = 20",
                "SELECT * FROM t LEFT JOIN u ON id = uid WHERE score >= 49")) {
            assertSameResults(sql);
        }
    }

    @Test void outOfDateIndexFallsBackToScanWithoutRebuilding() throws Exception {
        createIndexes();
        Path index = indexed.resolve("t.age.idx");
        byte[] before = Files.readAllBytes(index);
        Path csv = indexed.resolve("t.csv");
        Files.writeString(csv, "id,name,age,score\n7,a,5,0\n8,b,6,0\n");
        Files.setLastModifiedTime(csv, FileTime.fromMillis(System.currentTimeMillis() + 10_000));

        Engine engine = new Engine(indexed);
        assertEquals(List.of(List.of(7)), rows(engine.execute("SELECT id FROM t WHERE age = 5")));
        assertArrayEquals(before, Files.readAllBytes(index), "a query must not rebuild the index");

        assertEquals("Rebuilt index idx_age on t(age)", engine.execute("CREATE INDEX idx_age ON t (age)").message());
        assertEquals(List.of(List.of(8)), rows(engine.execute("SELECT id FROM t WHERE age = 6")));
    }

    @Test void showIndexesListsNamesTablesColumnsAndStatusWithoutChangingFiles() throws Exception {
        Engine engine = new Engine(indexed);
        QueryResult empty = engine.execute("SHOW INDEXES;");
        assertNull(empty.message());
        assertEquals(List.of("name", "table", "column", "status"), empty.columnNames());
        assertTrue(empty.rows().isEmpty());

        createIndexes();
        engine.execute("CREATE INDEX by_uid ON u (uid)");
        assertEquals(List.of(
                List.of("idx_age", "t", "age", "fresh"),
                List.of("idx_id", "t", "id", "fresh"),
                List.of("idx_score", "t", "score", "fresh"),
                List.of("by_uid", "u", "uid", "fresh")), rows(engine.execute("show indexes")));

        Path csv = indexed.resolve("t.csv");
        Files.writeString(csv, "id,name,age,score\n7,a,5,0\n");
        Files.setLastModifiedTime(csv, FileTime.fromMillis(System.currentTimeMillis() + 10_000));
        Files.write(indexed.resolve("u.age.idx"), new byte[10]);
        Files.delete(indexed.resolve("u.csv"));
        byte[] before = Files.readAllBytes(indexed.resolve("t.age.idx"));

        QueryResult listed = engine.execute("SHOW INDEX");
        List<Object> unreadable = new java.util.ArrayList<>();
        unreadable.add(null);
        unreadable.addAll(List.of("u", "age", "unreadable"));
        assertEquals(List.of(
                List.of("idx_age", "t", "age", "stale"),
                List.of("idx_id", "t", "id", "stale"),
                List.of("idx_score", "t", "score", "stale"),
                unreadable,
                List.of("by_uid", "u", "uid", "stale")), rows(listed));
        assertArrayEquals(before, Files.readAllBytes(indexed.resolve("t.age.idx")));
        assertEquals(5, indexFiles(indexed));

        engine.execute("DROP INDEX " + rows(listed).get(0).get(0));
        assertEquals(4, engine.execute("SHOW INDEXES").rows().size());
    }
}
