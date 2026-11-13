package minidb.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import minidb.Engine;
import minidb.catalog.Column;
import minidb.catalog.Table;
import minidb.storage.DirectoryTableStore;
import minidb.storage.Row;
import minidb.types.DataType;
import minidb.types.Value;

class StatisticsCliTest {
    @TempDir Path data;
    Engine engine;
    private static final Table TABLE = new Table("students", List.of(
            new Column("id", DataType.INTEGER), new Column("name", DataType.STRING)));

    @BeforeEach void prepare() {
        new DirectoryTableStore(data).write(TABLE,
                List.of(row(1, "Alice"), row(1, ""), row(null, null)));
        engine = new Engine(data);
    }

    private static Row row(Integer id, String name) {
        return new Row(List.of(new Value(DataType.INTEGER, id), new Value(DataType.STRING, name)));
    }

    private String run(String input) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new Cli(engine).run(new StringReader(input), new PrintStream(bytes, true, StandardCharsets.UTF_8));
        return bytes.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test void displaysStatisticsAndReusesTheEngineCacheAcrossSqlAndEnglishQueries() throws Exception {
        var expectedRows = engine.execute("SELECT * FROM students").rows();
        assertTrue(engine.cachedStatistics("students").isEmpty());
        String text = run(".stats students\nSELECT id FROM students\nshow id from students\n"
                + ".STATS\tstudents;\nquit\n");
        assertTrue(text.contains("Collecting statistics for students (full scan)..."), text);
        assertTrue(text.contains("Using cached statistics for students."), text);
        assertTrue(text.contains("Table: students\nRows: 3\nCSV bytes: "
                + Files.size(data.resolve("students.csv")) + "\nCollected at (UTC): "), text);
        assertTrue(text.contains("Column | Type | NULLs | Distinct non-NULL | Accuracy\n"), text);
        assertTrue(text.contains("id | INTEGER | 1 | 1 | exact\n"), text);
        assertTrue(text.contains("name | STRING | 1 | 2 | exact\n"), text);
        assertEquals(2, text.split("\\(3 rows\\)", -1).length - 1, text);
        assertEquals(expectedRows, engine.execute("SELECT * FROM students").rows());
        assertSame(engine.cachedStatistics("students").orElseThrow(), engine.statistics("students"));
    }

    @Test void distinguishesExactAndEstimatedDistinctCountsAndHandlesEmptyAndAllNullTables() {
        var store = new DirectoryTableStore(data);
        for (int count : List.of(256, 257)) {
            store.write(new Table("values" + count, TABLE.columns()),
                    () -> IntStream.range(0, count).mapToObj(i -> row(i, "same")).iterator());
        }
        store.write(new Table("empty", TABLE.columns()), List.of());
        store.write(new Table("nulls", TABLE.columns()), List.of(row(null, null), row(null, null)));
        String text = run(".stats values256\n.stats values257\n.stats empty\n.stats nulls\nquit\n");
        assertTrue(text.contains("id | INTEGER | 0 | 256 | exact\n"), text);
        assertTrue(text.contains("id | INTEGER | 0 | 257 | estimated\n"), text);
        assertTrue(text.contains("Table: empty\nRows: 0\n"), text);
        assertTrue(text.contains("name | STRING | 0 | 0 | exact\n"), text);
        assertTrue(text.contains("Table: nulls\nRows: 2\n"), text);
        assertTrue(text.contains("id | INTEGER | 2 | 0 | exact\n"), text);
    }

    @Test void statsRefreshesOnExternalEditsAndAnalyzeForcesMetadataPreservingRefresh() throws Exception {
        run(".stats students\nquit\n");
        Path csv = data.resolve("students.csv");
        Files.writeString(csv, "2,Bob\n", StandardOpenOption.APPEND);
        String refreshed = run(".stats students\nquit\n");
        assertTrue(refreshed.contains("Collecting statistics for students (full scan)..."), refreshed);
        assertTrue(refreshed.contains("Rows: 4\n"), refreshed);
        assertTrue(refreshed.contains("id | INTEGER | 1 | 2 | exact\n"), refreshed);
        var original = engine.cachedStatistics("students").orElseThrow();
        FileTime modified = Files.getLastModifiedTime(csv);
        Files.writeString(csv, Files.readString(csv).replace("2,Bob", "1,Bob"));
        Files.setLastModifiedTime(csv, modified);
        assertSame(original, engine.cachedStatistics("students").orElseThrow());
        String analyzed = run(".ANALYZE students ;\n.stats students\nquit\n");
        assertTrue(analyzed.contains("Analyzing students (full scan)..."), analyzed);
        assertTrue(analyzed.contains("id | INTEGER | 1 | 1 | exact\n"), analyzed);
        assertTrue(analyzed.contains("Using cached statistics for students."), analyzed);
        assertNotSame(original, engine.cachedStatistics("students").orElseThrow());
    }

    @Test void invalidCommandsAndStorageFailuresDoNotEndTheSessionOrPrintStaleStatistics() throws Exception {
        engine.statistics("students");
        Files.writeString(data.resolve("students.csv"), "bad,Bob\n", StandardOpenOption.APPEND);
        String text = run(".stats\n.analyze students extra\n.unknown students\n.stats ../students\n"
                + ".stats missing\n.stats students\n.analyze students\n"
                + "SELECT id FROM students LIMIT 1\nquit\n");
        assertTrue(text.contains("Usage: .stats <table>"), text);
        assertTrue(text.contains("Usage: .analyze <table>"), text);
        assertTrue(text.contains("Unknown command: .unknown"), text);
        assertTrue(text.contains("Invalid identifier:"), text);
        assertTrue(text.contains("Cannot collect statistics for missing"), text);
        assertTrue(text.contains("expected 32-bit INTEGER"), text);
        assertTrue(text.contains("(1 rows)"), text);
        assertFalse(text.contains("Table: students"), text);
        assertFalse(text.contains("Using cached statistics"), text);
        assertTrue(engine.cachedStatistics("students").isEmpty());
    }
}
