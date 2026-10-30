package minidb;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import minidb.plan.PlanningException;
import minidb.sql.SqlParseException;

class JoinTest {
    @TempDir Path data;
    Engine engine;
    @BeforeEach void prepare() throws Exception {
        Files.writeString(data.resolve("a.schema"), "id INTEGER\nname STRING\n");
        Files.writeString(data.resolve("a.csv"), "id,name\n1,A\n1,B\n2,C\n");
        Files.writeString(data.resolve("b.schema"), "id INTEGER\nlabel STRING\n");
        Files.writeString(data.resolve("b.csv"), "id,label\n1,X\n1,Y\n3,Z\n");
        engine = new Engine(data);
    }
    List<List<Object>> rows(String sql) {
        return engine.execute(sql).rows().stream().map(r -> r.values().stream().map(v -> v.raw()).toList()).toList();
    }
    @Test void joinsDuplicatesAndBindsBothSides() {
        assertEquals(List.of(List.of("A", "X"), List.of("A", "Y"), List.of("B", "X"), List.of("B", "Y")),
                rows("SELECT name, label FROM a INNER JOIN b ON a.id = b.id"));
        var result = engine.execute("SELECT * FROM a JOIN b ON b.id = a.id");
        assertEquals(List.of("id", "name", "id", "label"), result.columnNames());
        assertEquals(List.of(1, "A", 1, "X"), rows("SELECT * FROM a JOIN b ON a.id = b.id").get(0));
    }
    @Test void aliasesSelfJoinWhereSortLimitAndStringKeys() {
        assertEquals(List.of(List.of("B")), rows("SELECT x.name FROM a AS x JOIN b y ON x.id = y.id "
                + "WHERE y.label = 'X' ORDER BY x.name DESC LIMIT 1"));
        assertEquals(5, rows("SELECT x.id FROM a x JOIN a y ON x.id = y.id").size());
        assertEquals(3, rows("SELECT x.id FROM a x JOIN a y ON x.name = y.name").size());
        assertTrue(rows("SELECT * FROM a JOIN b ON a.id = b.id LIMIT 0").isEmpty());
    }
    @Test void emptyAndUnmatchedInputs() throws Exception {
        Files.writeString(data.resolve("b.csv"), "id,label\n9,Z\n");
        assertTrue(rows("SELECT * FROM a JOIN b ON a.id = b.id").isEmpty());
        Files.writeString(data.resolve("b.csv"), "id,label\n");
        assertTrue(rows("SELECT * FROM a JOIN b ON a.id = b.id").isEmpty());
        Files.writeString(data.resolve("a.csv"), "id,name\n");
        assertTrue(rows("SELECT * FROM a JOIN b ON a.id = b.id").isEmpty());
    }
    @Test void rejectsInvalidBindingsAndConditions() {
        for (String sql : List.of(
                "SELECT id FROM a JOIN b ON a.id = b.id",
                "SELECT * FROM a JOIN b ON id = b.id",
                "SELECT * FROM a JOIN b ON a.id = b.label",
                "SELECT * FROM a JOIN b ON a.id = a.id",
                "SELECT * FROM a JOIN b ON a.id > b.id",
                "SELECT * FROM a JOIN b ON a.id = 1",
                "SELECT * FROM a x JOIN b x ON x.id = x.id",
                "SELECT a.id FROM a x JOIN b y ON x.id = y.id",
                "SELECT q.id FROM a JOIN b ON a.id = b.id",
                "SELECT * FROM a JOIN b ON a.missing = b.id",
                "SELECT * FROM a JOIN b ON a.id = b.id ORDER BY id")) {
            assertThrows(PlanningException.class, () -> engine.execute(sql), sql);
        }
        assertThrows(minidb.storage.StorageException.class,
                () -> engine.execute("SELECT * FROM a JOIN missing ON a.id = missing.id"));
        for (String sql : List.of("SELECT * FROM a JOIN b", "SELECT * FROM a FULL JOIN b ON a.id = b.id",
                "SELECT * FROM a JOIN b ON a.id = b.id JOIN a c ON c.id = b.id")) {
            assertThrows(SqlParseException.class, () -> engine.execute(sql), sql);
        }
    }
}
