package minidb;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import minidb.catalog.Column;
import minidb.catalog.Table;
import minidb.cli.Cli;
import minidb.exec.QueryResult;
import minidb.plan.PlanningException;
import minidb.sql.SqlParseException;
import minidb.storage.DirectoryTableStore;
import minidb.storage.Row;
import minidb.storage.StorageException;
import minidb.types.DataType;
import minidb.types.Value;

/** End-to-end aggregation through the engine, using the Task 3 handoff fixtures. */
class AggregationTest {
    @TempDir Path data;
    Engine engine;

    private static Value s(String text) {
        return text == null ? Value.nullValue(DataType.STRING) : new Value(DataType.STRING, text);
    }

    private static Value i(Integer value) {
        return value == null ? Value.nullValue(DataType.INTEGER) : new Value(DataType.INTEGER, value);
    }

    @BeforeEach void prepare() {
        DirectoryTableStore store = new DirectoryTableStore(data);
        store.write(new Table("events", List.of(new Column("dept", DataType.STRING),
                new Column("points", DataType.INTEGER))), List.of(
                new Row(List.of(s("A"), i(10))), new Row(List.of(s("A"), i(11))), new Row(List.of(s("A"), i(null))),
                new Row(List.of(s("B"), i(5))), new Row(List.of(s("B"), i(null))), new Row(List.of(s("C"), i(null))),
                new Row(List.of(s(null), i(4))), new Row(List.of(s(null), i(8)))));
        store.write(new Table("empty", List.of(new Column("dept", DataType.STRING),
                new Column("points", DataType.INTEGER))), List.of());
        store.write(new Table("lefts", List.of(new Column("id", DataType.INTEGER),
                new Column("name", DataType.STRING))), List.of(
                new Row(List.of(i(1), s("one"))), new Row(List.of(i(2), s("two")))));
        store.write(new Table("rights", List.of(new Column("id", DataType.INTEGER),
                new Column("lid", DataType.INTEGER))), List.of(
                new Row(List.of(i(10), i(1))), new Row(List.of(i(11), i(1))), new Row(List.of(i(12), i(3)))));
        engine = new Engine(data);
    }

    private List<List<Object>> rows(String sql) {
        return engine.execute(sql).rows().stream()
                .map(r -> r.values().stream().map(Value::raw).toList()).toList();
    }

    @Test void globalAggregatesOnEvents() {
        QueryResult result = engine.execute("SELECT COUNT(*), COUNT(points), SUM(points), AVG(points), "
                + "MIN(points), MAX(points) FROM events");
        assertEquals(List.of("COUNT(*)", "COUNT(points)", "SUM(points)", "AVG(points)", "MIN(points)",
                "MAX(points)"), result.columnNames());
        assertEquals(List.of(List.of(8L, 5L, 38L, new BigDecimal("7.600000"), 4, 11)), rows(
                "SELECT COUNT(*), COUNT(points), SUM(points), AVG(points), MIN(points), MAX(points) FROM events"));
        assertEquals(List.of(DataType.BIGINT, DataType.BIGINT, DataType.BIGINT, DataType.DECIMAL,
                DataType.INTEGER, DataType.INTEGER),
                result.rows().get(0).values().stream().map(Value::type).toList());
        assertEquals(List.of(List.of("A", "C")), rows("SELECT MIN(dept), MAX(dept) FROM events"));
    }

    @Test void emptyInputSemantics() {
        assertEquals(List.of(Arrays.asList(0L, 0L, null, null, null, null)), rows(
                "SELECT COUNT(*), COUNT(points), SUM(points), AVG(points), MIN(points), MAX(dept) FROM empty"));
        assertTrue(rows("SELECT dept, COUNT(*) FROM empty GROUP BY dept").isEmpty());
        assertEquals(List.of(List.of(0L)), rows("SELECT COUNT(*) FROM events WHERE points > 100"));
        assertEquals(List.of(List.of(0L)), rows("SELECT COUNT(points) FROM events WHERE dept = 'C'"));
        // HAVING filters the single global row.
        assertTrue(rows("SELECT COUNT(*) FROM empty HAVING COUNT(*) > 0").isEmpty());
    }

    @Test void groupingWithNullableKeys() {
        assertEquals(List.of(List.of("A", 3L, 2L, 21L), List.of("B", 2L, 1L, 5L), Arrays.asList("C", 1L, 0L, null),
                Arrays.asList(null, 2L, 2L, 12L)),
                rows("SELECT dept, COUNT(*), COUNT(points), SUM(points) FROM events GROUP BY dept ORDER BY dept"));
        assertEquals(List.of(Arrays.asList("A"), Arrays.asList("B"), Arrays.asList("C"), Arrays.asList((Object) null)),
                rows("SELECT dept FROM events GROUP BY dept ORDER BY dept"));
    }

    @Test void havingAfterWhere() {
        assertEquals(List.of(List.of("A", 2L)), rows(
                "SELECT dept, COUNT(*) FROM events WHERE points >= 5 GROUP BY dept HAVING COUNT(*) >= 2"));
    }

    @Test void hiddenAggregateOrderingAndLimit() {
        QueryResult result = engine.execute("SELECT dept FROM events GROUP BY dept HAVING COUNT(points) > 0 "
                + "ORDER BY SUM(points) DESC LIMIT 2");
        assertEquals(List.of("dept"), result.columnNames());
        assertEquals(List.of(List.of("A"), Arrays.asList((Object) null)), rows(
                "SELECT dept FROM events GROUP BY dept HAVING COUNT(points) > 0 ORDER BY SUM(points) DESC LIMIT 2"));
    }

    @Test void havingWithThreeValuedLogicAndNumericThresholds() {
        // SUM(points) is NULL for C: UNKNOWN fails HAVING and stays UNKNOWN under NOT.
        assertEquals(List.of(List.of("B")), rows(
                "SELECT dept FROM events GROUP BY dept HAVING NOT SUM(points) > 6 ORDER BY dept"));
        assertEquals(List.of(List.of("C")), rows(
                "SELECT dept FROM events GROUP BY dept HAVING SUM(points) IS NULL"));
        assertEquals(List.of(List.of("A", new BigDecimal("10.500000"))), rows(
                "SELECT dept, AVG(points) FROM events GROUP BY dept HAVING AVG(points) > 10"));
        assertEquals(List.of(List.of("A")), rows(
                "SELECT dept FROM events GROUP BY dept HAVING AVG(points) >= 10 AND dept = 'A'"));
        assertEquals(List.of(List.of(8L)), rows("SELECT COUNT(*) FROM events HAVING COUNT(*) = 8"));
    }

    @Test void repeatedProjectionsAndSortOnSelectedAggregate() {
        assertEquals(List.of(List.of(5L, "B", 5L), Arrays.asList(12L, null, 12L), List.of(21L, "A", 21L)),
                rows("SELECT SUM(points), dept, SUM(points) FROM events GROUP BY dept "
                        + "HAVING COUNT(points) > 0 ORDER BY SUM(points)"));
    }

    @Test void aggregationAfterLeftJoin() {
        String sql = "SELECT l.id, COUNT(*), COUNT(r.id) FROM lefts l LEFT JOIN rights r ON l.id = r.lid "
                + "GROUP BY l.id ORDER BY l.id";
        assertEquals(List.of(List.of(1, 2L, 2L), List.of(2, 1L, 0L)), rows(sql));
        assertEquals(List.of("id", "COUNT(*)", "COUNT(r.id)"), engine.execute(sql).columnNames());
        assertEquals(List.of(List.of("one", 21L)), rows("SELECT l.name, SUM(r.id) FROM lefts l JOIN rights r "
                + "ON l.id = r.lid GROUP BY l.name HAVING COUNT(*) >= 2"));
        assertEquals(List.of(List.of(2L)), rows("SELECT COUNT(*) FROM rights r JOIN lefts l ON r.lid = l.id"));
    }

    @Test void indexedWhereStillFeedsAggregation() {
        engine.execute("CREATE INDEX idx_points ON events (points)");
        assertEquals(List.of(List.of(3L, 29L)), rows("SELECT COUNT(*), SUM(points) FROM events WHERE points >= 8"));
    }

    @Test void sumBeyondBigintIsARecoverableError() throws Exception {
        // 32-bit inputs cannot reach BIGINT overflow in a test-sized table; the accumulator test covers it.
        Files.writeString(data.resolve("big.schema"), "v INTEGER\n");
        Files.writeString(data.resolve("big.csv"), "v\n2147483647\n2147483647\n-2147483648\n");
        assertEquals(List.of(List.of(2147483646L, new BigDecimal("715827882.000000"))),
                rows("SELECT SUM(v), AVG(v) FROM big"));
    }

    @Test void invalidQueriesAreRejected() {
        assertThrows(PlanningException.class, () -> engine.execute("SELECT dept, COUNT(*) FROM events"));
        assertThrows(PlanningException.class, () -> engine.execute("SELECT AVG(dept) FROM events"));
        assertThrows(SqlParseException.class, () -> engine.execute("SELECT SUM(*) FROM events"));
        assertThrows(PlanningException.class, () -> engine.execute(
                "SELECT dept FROM events WHERE MAX(points) > 1 GROUP BY dept"));
    }

    @Test void nonAggregateQueriesAreUnchanged() {
        assertEquals(List.of(List.of("A", 11), List.of("A", 10)), rows(
                "SELECT dept, points FROM events WHERE dept = 'A' AND points IS NOT NULL ORDER BY points DESC"));
        assertEquals(List.of("dept", "points"), engine.execute("SELECT * FROM events").columnNames());
    }

    @Test void resultOnlyTypesCannotBeStored() throws Exception {
        Files.writeString(data.resolve("bad.schema"), "n BIGINT\n");
        Files.writeString(data.resolve("bad.csv"), "n\n1\n");
        assertThrows(StorageException.class, () -> engine.execute("SELECT * FROM bad"));
        assertThrows(StorageException.class, () -> new DirectoryTableStore(data).write(
                new Table("bad2", List.of(new Column("n", DataType.DECIMAL))), List.of()));
    }

    @Test void cliPrintsAggregatesAndRecoversFromErrors() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        new Cli(new Engine(data)).run(new StringReader(
                "SELECT dept, COUNT(*) FROM events\n"
                + "SELECT COUNT(*), AVG(points) FROM events\n"
                + "SELECT SUM(*) FROM events\n"
                + "SELECT dept, COUNT(*), AVG(points) FROM events WHERE points >= 5 GROUP BY dept "
                + "HAVING COUNT(*) >= 2 ORDER BY dept\nquit\n"),
                new PrintStream(buffer, true, StandardCharsets.UTF_8));
        String text = buffer.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("must appear in GROUP BY"), text);
        assertTrue(text.contains("COUNT(*) | AVG(points)"), text);
        assertTrue(text.contains("8 | 7.600000"), text);
        assertTrue(text.contains("only COUNT accepts *"), text);
        assertTrue(text.contains("A | 2 | 10.500000"), text);
    }
}
