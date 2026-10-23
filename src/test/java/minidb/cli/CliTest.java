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
        int joinError = text.indexOf("JOIN is not supported");
        assertTrue(parseError >= 0, text);
        assertTrue(queryResult > parseError, text);
        assertTrue(joinError > queryResult, text);
        assertEquals(4, text.split("minidb>", -1).length - 1);
    }
}
