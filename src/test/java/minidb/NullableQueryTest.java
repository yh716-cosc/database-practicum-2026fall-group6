package minidb;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import minidb.catalog.Column;
import minidb.catalog.Table;
import minidb.plan.PlanningException;
import minidb.sql.SqlParseException;
import minidb.storage.DirectoryTableStore;
import minidb.storage.Row;
import minidb.types.DataType;
import minidb.types.Value;

class NullableQueryTest {
    @TempDir Path data;
    Engine engine;

    @BeforeEach void prepare() {
        Table table = new Table("people", List.of(new Column("id", DataType.INTEGER),
                new Column("name", DataType.STRING), new Column("age", DataType.INTEGER)));
        new DirectoryTableStore(data).write(table, List.of(
                row(1, "NULL", null), row(2, null, 20), row(3, "", 25), row(4, "\\N", null)));
        engine = new Engine(data);
    }

    private static Row row(int id, String name, Integer age) {
        return new Row(List.of(new Value(DataType.INTEGER, id), new Value(DataType.STRING, name),
                new Value(DataType.INTEGER, age)));
    }

    private List<Integer> ids(String predicate) {
        return engine.execute("SELECT id FROM people WHERE " + predicate).rows().stream()
                .map(r -> (Integer) r.values().get(0).raw()).toList();
    }

    @Test void freshEngineReadsTypedNullsAndQueriesNullness() {
        assertEquals(Arrays.asList(1, "NULL", null), engine.execute("SELECT * FROM people")
                .rows().get(0).values().stream().map(Value::raw).toList());
        assertEquals(List.of(1, 4), ids("age IS NULL"));
        assertEquals(List.of(2, 3), ids("age is nOt null"));
        assertEquals(List.of(2), ids("people.name IS NULL"));
        assertEquals(List.of(1, 3, 4), ids("name IS NOT NULL"));
        assertEquals(List.of(1), ids("name = 'NULL'"));
        assertEquals(List.of(3), ids("name = ''"));
        assertEquals(List.of(4), ids("name = '\\N'"));
    }

    @Test void unknownComparisonsStayUnknownThroughNotAndBooleanCombinations() {
        assertEquals(List.of(2, 3), ids("age >= 20"));
        assertEquals(List.of(2, 3), ids("NOT (age < 20)"));
        assertEquals(List.of(1, 2, 3, 4), ids("age >= 20 OR age IS NULL"));
        assertEquals(List.of(2, 3), ids("age >= 20 AND age IS NOT NULL"));
        assertEquals(List.of(3), ids("NOT (age = 20 OR name IS NULL)"));
        assertTrue(ids("age <> age").isEmpty());
        assertEquals(List.of(2, 3), ids("age = age"));
    }

    @Test void printsNullDistinctlyFromLiteralNullAndKeepsValidation() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        engine.execute("SELECT name FROM people").printTo(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        assertEquals("'NULL'\nNULL\n''\n'\\N'\n(4 rows)\n", bytes.toString(StandardCharsets.UTF_8));
        assertThrows(PlanningException.class, () -> ids("missing IS NULL"));
        assertThrows(PlanningException.class, () -> ids("age = '20'"));
        assertThrows(SqlParseException.class, () -> ids("age = NULL"));
        assertThrows(SqlParseException.class, () -> ids("age IS NOT 1"));
    }
}
