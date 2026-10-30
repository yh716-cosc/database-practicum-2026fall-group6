package minidb;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.io.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import minidb.sql.*;
import minidb.sql.ast.*;

class OuterJoinTest {
    @TempDir Path data;
    Engine engine;
    @BeforeEach void prepare() throws Exception {
        Files.writeString(data.resolve("a.schema"), "id INTEGER\nname STRING\n");
        Files.writeString(data.resolve("a.csv"), "id,name\n1,A\n1,B\n2,C\n2,C\n");
        Files.writeString(data.resolve("b.schema"), "id INTEGER\nlabel STRING\n");
        Files.writeString(data.resolve("b.csv"), "id,label\n1,X\n1,Y\n3,Z\n3,Z\n");
        engine = new Engine(data);
    }
    List<List<Object>> rows(String sql) {
        return engine.execute(sql).rows().stream().map(r -> r.values().stream().map(v -> v.raw()).toList()).toList();
    }
    @Test void leftPreservesUnmatchedDuplicatesWithTypedNulls() {
        String sql = "SELECT * FROM a x LEFT OUTER JOIN b y ON x.id = y.id";
        var result = engine.execute(sql);
        assertEquals(6, result.rows().size());
        assertEquals(Arrays.asList(2, "C", null, null), rows(sql).get(4));
        assertEquals(rows(sql).get(4), rows(sql).get(5));
        assertEquals(minidb.types.DataType.INTEGER, result.rows().get(4).values().get(2).type());
        assertEquals(minidb.types.DataType.STRING, result.rows().get(4).values().get(3).type());
        assertEquals(List.of("id", "name", "id", "label"), result.columnNames());
    }
    @Test void rightKeepsLogicalColumnLayoutAndBinding() {
        String sql = "SELECT * FROM a x RIGHT JOIN b y ON y.id = x.id";
        assertEquals(6, rows(sql).size());
        assertEquals(Arrays.asList(null, null, 3, "Z"), rows(sql).get(4));
        assertEquals(List.of("id", "name", "id", "label"), engine.execute(sql).columnNames());
        assertEquals(List.of(List.of("Z", 3), List.of("Z", 3)), rows(
                "SELECT y.label, y.id FROM a x RIGHT OUTER JOIN b y ON x.id = y.id WHERE x.id IS NULL"));
        assertEquals(8, rows("SELECT * FROM a x RIGHT JOIN a y ON x.id = y.id").size());
    }
    @Test void predicatesUseThreeValuedLogicAfterNullExtension() {
        String base = "SELECT a.id FROM a LEFT JOIN b ON a.id = b.id WHERE ";
        assertEquals(2, rows(base + "b.id IS NULL").size());
        assertEquals(4, rows(base + "b.id IS NOT NULL").size());
        assertTrue(rows(base + "NOT (b.id = 1)").isEmpty());
        assertTrue(rows(base + "b.id <> 1").isEmpty());
        assertEquals(2, rows(base + "NOT (b.id = 1 AND a.id = 1)").size());
        assertEquals(2, rows(base + "b.id = 1 AND a.id = 2 OR b.id IS NULL").size());
        assertEquals(6, rows(base + "b.id = 1 OR a.id = 2").size());
    }
    @Test void emptySidesAndNoMatches() throws Exception {
        Files.writeString(data.resolve("b.csv"), "id,label\n");
        assertEquals(4, rows("SELECT * FROM a LEFT JOIN b ON a.id = b.id").size());
        assertTrue(rows("SELECT * FROM a RIGHT JOIN b ON a.id = b.id").isEmpty());
        Files.writeString(data.resolve("a.csv"), "id,name\n");
        Files.writeString(data.resolve("b.csv"), "id,label\n3,Z\n3,Z\n");
        assertEquals(2, rows("SELECT * FROM a RIGHT JOIN b ON a.id = b.id").size());
        assertTrue(rows("SELECT * FROM a LEFT JOIN b ON a.id = b.id").isEmpty());
        Files.writeString(data.resolve("a.csv"), "id,name\n2,C\n");
        assertEquals(List.of(Arrays.asList(2, "C", null, null)), rows("SELECT * FROM a LEFT JOIN b ON a.id = b.id"));
        assertEquals(2, rows("SELECT * FROM a RIGHT JOIN b ON a.id = b.id").size());
    }
    @Test void nullSortingLimitsAndDisplay() {
        String base = "SELECT b.id FROM a LEFT JOIN b ON a.id = b.id";
        assertNull(rows(base + " ORDER BY b.id").get(5).get(0));
        assertNull(rows(base + " ORDER BY b.id DESC LIMIT 1").get(0).get(0));
        assertTrue(rows(base + " LIMIT 0").isEmpty());
        var buffer = new ByteArrayOutputStream();
        engine.execute(base + " WHERE b.id IS NULL LIMIT 1").printTo(new PrintStream(buffer));
        assertTrue(buffer.toString().contains("NULL"));
        assertNull(rows("SELECT a.name FROM a RIGHT JOIN b ON a.id = b.id ORDER BY a.name DESC LIMIT 1")
                .get(0).get(0));
        assertEquals(2, rows("SELECT b.label FROM a RIGHT JOIN b ON a.id = b.id WHERE NOT (a.name = 'A')")
                .size());
    }

    @Test void csvErrorsPropagateButUnreadSidesRemainLazy() throws Exception {
        Files.writeString(data.resolve("b.csv"), "id,label\nbad,Z\n");
        assertThrows(minidb.storage.StorageException.class,
                () -> rows("SELECT * FROM a LEFT JOIN b ON a.id = b.id"));
        Files.writeString(data.resolve("a.csv"), "id,name\n");
        assertTrue(rows("SELECT * FROM a LEFT JOIN b ON a.id = b.id").isEmpty());
        Files.writeString(data.resolve("a.csv"), "id,name\nbad,A\n");
        Files.writeString(data.resolve("b.csv"), "id,label\n3,Z\n");
        assertThrows(minidb.storage.StorageException.class,
                () -> rows("SELECT * FROM a RIGHT JOIN b ON a.id = b.id"));
        Files.writeString(data.resolve("b.csv"), "id,label\n");
        assertTrue(rows("SELECT * FROM a RIGHT JOIN b ON a.id = b.id").isEmpty());
    }
    @Test void parsesTypesAndRejectsMalformedOuterJoins() {
        for (String word : List.of("LEFT", "RIGHT")) {
            var query = (Query) new SqlParser().parse("SELECT * FROM a " + word + " OUTER JOIN b ON a.id = b.id");
            assertEquals(Join.Type.valueOf(word), query.joins().get(0).type());
        }
        for (String sql : List.of("SELECT * FROM a LEFT b ON a.id = b.id",
                "SELECT * FROM a RIGHT JOIN b", "SELECT * FROM a FULL OUTER JOIN b ON a.id = b.id",
                "SELECT * FROM a WHERE id IS 1", "SELECT * FROM a WHERE id IS NOT")) {
            assertThrows(SqlParseException.class, () -> engine.execute(sql), sql);
        }
    }
}
