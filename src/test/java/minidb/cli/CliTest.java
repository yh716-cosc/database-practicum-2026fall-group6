package minidb.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import org.junit.jupiter.api.Test;

import minidb.Engine;

class CliTest {
    @TempDir Path data;

    @BeforeEach void prepareTable() throws Exception {
        Files.writeString(data.resolve("students.schema"), "id INTEGER\n");
        Files.writeString(data.resolve("students.csv"), "id\n1\n");
    }
    @Test
    void storageErrorsDoNotStopTheNextStatement() throws Exception {
        Files.writeString(data.resolve("broken.schema"), "id INTEGER\n");
        Files.writeString(data.resolve("broken.csv"), "id\nbad\n");
        Files.writeString(data.resolve("header.schema"), "id INTEGER\n");
        Files.writeString(data.resolve("header.csv"), "wrong\n1\n");
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        new Cli(new Engine(data)).run(new StringReader(
                "SELECT * FROM absent\nSELECT id FROM students\n"
                + "SELECT * FROM broken\nSELECT id FROM students\n"
                + "SELECT * FROM header\nSELECT id FROM students\nquit\n"),
                new PrintStream(buffer, true, StandardCharsets.UTF_8));
        String text = buffer.toString(StandardCharsets.UTF_8);
        assertEquals(3, text.split("\\(1 rows\\)", -1).length - 1, text);
        assertEquals(7, text.split("minidb>", -1).length - 1, text);
        assertTrue(text.contains("id\n--\n1\n".replace("\n", System.lineSeparator())), text);
        assertTrue(!text.contains("Value[type="), text);
    }

    @Test
    void createIndexPrintsStatusAndErrorsDoNotStopTheSession() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        new Cli(new Engine(data)).run(new StringReader(
                "CREATE INDEX idx_id ON students (missing)\nCREATE INDEX idx_id ON students (id);\n"
                + "SELECT id FROM students WHERE id = 1\nquit\n"),
                new PrintStream(buffer, true, StandardCharsets.UTF_8));
        String text = buffer.toString(StandardCharsets.UTF_8);
        int error = text.indexOf("Unknown column: students.missing");
        int created = text.indexOf("Created index idx_id on students(id)");
        assertTrue(error >= 0 && created > error, text);
        assertTrue(text.indexOf("(1 rows)") > created, text);
        assertTrue(Files.exists(data.resolve("students.id.idx")));
    }

    @Test
    void showIndexesPrintsATable() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        new Cli(new Engine(data)).run(new StringReader(
                "SHOW INDEXES;\nCREATE INDEX idx_id ON students (id)\nSHOW INDEXES\nquit\n"),
                new PrintStream(buffer, true, StandardCharsets.UTF_8));
        String text = buffer.toString(StandardCharsets.UTF_8);
        String nl = System.lineSeparator();
        assertTrue(text.contains("(0 rows)"), text);
        assertTrue(text.contains("name | table | column | status" + nl), text);
        assertTrue(text.contains("idx_id | students | id | fresh" + nl + "(1 rows)"), text);
    }

    @Test
    void planningErrorDoesNotStopTheNextStatement() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream output = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        new Cli(new Engine(data)).run(new StringReader(
                "SELECT missing FROM students\nSELECT id FROM students\nquit\n"), output);
        String text = buffer.toString(StandardCharsets.UTF_8);
        int error = text.indexOf("Unknown column: missing");
        assertTrue(error >= 0, text);
        assertTrue(text.indexOf("(1 rows)") > error, text);
    }

    @Test
    void excessiveNestingDoesNotTerminateTheSession() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream output = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        String prefix = "SELECT a FROM t WHERE ";
        String input = prefix + "(".repeat(10_000) + "a=1\n"
                + "SELECT id FROM students\n"
                + prefix + "NOT ".repeat(10_000) + "a=1\n"
                + "SELECT id FROM students\n"
                + prefix + "NOT (".repeat(5_000) + "a=1" + ")".repeat(5_000) + "\n"
                + "SELECT id FROM students\nquit\n";
        new Cli(new Engine(data)).run(new StringReader(input), output);

        String text = buffer.toString(StandardCharsets.UTF_8);
        int previous = -1;
        for (int i = 0; i < 3; i++) {
            int error = text.indexOf("predicate nesting exceeds limit of 128", previous + 1);
            int result = text.indexOf("(1 rows)", error + 1);
            assertTrue(error > previous, text);
            assertTrue(result > error, text);
            previous = result;
        }
        assertEquals(7, text.split("minidb>", -1).length - 1);
    }

    @Test
    void timingOnPrintsElapsedTimeAfterSuccessfulStatementsOnly() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream output = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        String input = """
                SELECT id FROM students
                \\timing on
                SELECT id FROM students
                SELECT missing FROM students
                \\timing off;
                SELECT id FROM students
                quit
                """;
        new Cli(new Engine(data)).run(new StringReader(input), output);

        String text = buffer.toString(StandardCharsets.UTF_8);
        int on = text.indexOf("Timing is on.");
        int off = text.indexOf("Timing is off.");
        assertTrue(on >= 0 && off > on, text);
        assertEquals(1, text.split("Time: \\d+\\.\\d{3} ms", -1).length - 1, text);
        int time = text.indexOf("Time: ");
        assertTrue(time > text.indexOf("(1 rows)", on) && time < text.indexOf("Unknown column"), text);
    }

    @Test
    void metaCommandsToggleAndRejectUnknownInput() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream output = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        String input = """
                \\timing
                \\timing maybe
                \\TIMING
                \\foo
                SELECT id FROM students
                quit
                """;
        new Cli(new Engine(data)).run(new StringReader(input), output);

        String text = buffer.toString(StandardCharsets.UTF_8);
        int on = text.indexOf("Timing is on.");
        int bad = text.indexOf("\\timing: unrecognized value \"maybe\": Boolean expected");
        int off = text.indexOf("Timing is off.");
        int invalid = text.indexOf("Invalid command \\foo.");
        assertTrue(on >= 0 && bad > on && off > bad && invalid > off, text);
        assertTrue(text.indexOf("(1 rows)") > invalid, text);
        assertTrue(!text.contains("Time: "), text);
        assertEquals(6, text.split("minidb>", -1).length - 1, text);
    }

    @Test
    void parseErrorDoesNotStopTheNextStatement() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream output = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        String input = """
                SELECT FROM t
                SELECT id FROM students
                SELECT a FROM t JOIN u
                quit
                """;
        new Cli(new Engine(data)).run(new StringReader(input), output);

        String text = buffer.toString(StandardCharsets.UTF_8);
        int parseError = text.indexOf("expected column name");
        int queryResult = text.indexOf("(1 rows)");
        int joinError = text.indexOf("expected ON");
        assertTrue(parseError >= 0, text);
        assertTrue(queryResult > parseError, text);
        assertTrue(joinError > queryResult, text);
        assertEquals(4, text.split("minidb>", -1).length - 1);
    }

    @Test
    void englishCommandExecutesAndRecoversFromCommandError() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream output = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        new Cli(new Engine(data)).run(new StringReader(
                "show id from students\nshow all students\nSELECT id FROM students\nquit\n"), output);

        String text = buffer.toString(StandardCharsets.UTF_8);
        int commandError = text.indexOf("expected 'from'");
        int result = text.indexOf("(1 rows)", commandError + 1);
        assertTrue(commandError >= 0, text);
        assertTrue(result > commandError, text);
    }

    @Test
    void englishCommandSupportsLimit() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream output = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        new Cli(new Engine(data)).run(new StringReader(
                "show id from students return at most 1 rows\nquit\n"), output);

        String text = buffer.toString(StandardCharsets.UTF_8);
        assertTrue(text.replace("\r\n", "\n").contains("1\n(1 rows)"), text);
    }
}
