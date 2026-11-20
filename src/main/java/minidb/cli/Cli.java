package minidb.cli;

import java.io.PrintStream;
import java.util.Locale;
import java.util.Scanner;
import java.util.regex.Pattern;

import minidb.Engine;
import minidb.command.EnglishCommandException;
import minidb.command.EnglishCommandTranslator;
import minidb.exec.QueryResult;
import minidb.sql.SqlParseException;
import minidb.plan.PlanningException;
import minidb.storage.StorageException;
import minidb.storage.TableStatistics;

/** Reads queries and statistics commands, one per line. Table data stays read-only. */
public final class Cli {
    private static final Pattern SHOW_INDEXES =
            Pattern.compile("(?i)show\\s+index(es)?\\s*;?");

    private final Engine engine;
    private final EnglishCommandTranslator englishTranslator = new EnglishCommandTranslator();
    private boolean timing;

    public Cli(Engine engine) {
        this.engine = engine;
    }

    public void run() {
        run(new Scanner(System.in), System.out);
    }

    /** Reads statements from {@code input}. Used by tests and by {@link #run()}. */
    public void run(Readable input, PrintStream output) {
        run(new Scanner(input), output);
    }

    private void run(Scanner scanner, PrintStream output) {
        output.println("minidb skeleton. Type a query, .stats <table>, .analyze <table>, or quit.");
        while (true) {
            output.print("minidb> ");
            if (!scanner.hasNextLine()) {
                break;
            }
            String line = scanner.nextLine().trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.equalsIgnoreCase("quit") || line.equalsIgnoreCase("exit")) {
                break;
            }
            if (line.startsWith("\\")) {
                handleMetaCommand(line, output);
                continue;
            }
            try {
                if (line.startsWith(".")) {
                    statisticsCommand(line, output);
                    continue;
                }
                long start = System.nanoTime();
                String sql = isEnglishCommand(line) ? englishTranslator.translate(line) : line;
                QueryResult result = engine.execute(sql);
                long elapsed = System.nanoTime() - start;
                result.printTo(output);
                if (timing) {
                    output.println(String.format(Locale.ROOT, "Time: %.3f ms", elapsed / 1e6));
                }
            } catch (EnglishCommandException | SqlParseException | PlanningException
                    | StorageException ex) {
                output.println(ex.getMessage());
            } catch (UnsupportedOperationException ex) {
                output.println(ex.getMessage());
            }
        }
    }

    private void statisticsCommand(String input, PrintStream output) {
        String commandLine = input.endsWith(";") ? input.substring(0, input.length() - 1).trim() : input;
        String[] words = commandLine.split("\\s+");
        String command = words[0].toLowerCase(Locale.ROOT);
        if (!command.equals(".stats") && !command.equals(".analyze")) {
            output.println("Unknown command: " + words[0]
                    + ". Available commands: .stats <table>, .analyze <table>.");
            return;
        }
        if (words.length != 2) {
            output.println("Usage: " + command + " <table>");
            return;
        }
        String tableName = words[1];
        TableStatistics statistics;
        if (command.equals(".analyze")) {
            output.println("Analyzing " + tableName + " (full scan)...");
            statistics = engine.analyze(tableName);
        } else {
            var cached = engine.cachedStatistics(tableName);
            if (cached.isPresent()) {
                output.println("Using cached statistics for " + tableName + ".");
                statistics = cached.get();
            } else {
                output.println("Collecting statistics for " + tableName + " (full scan)...");
                statistics = engine.statistics(tableName);
            }
        }
        printStatistics(statistics, output);
    }

    private static void printStatistics(TableStatistics statistics, PrintStream output) {
        output.println("Table: " + statistics.tableName());
        output.println("Rows: " + statistics.rowCount());
        output.println("CSV bytes: " + statistics.dataBytes());
        output.println("Collected at (UTC): " + statistics.collectedAt());
        output.println("Column | Type | NULLs | Distinct non-NULL | Accuracy");
        statistics.columns().forEach((name, column) -> output.println(name + " | " + column.type()
                + " | " + column.nullCount() + " | " + column.distinctCount()
                + " | " + (column.distinctCountExact() ? "exact" : "estimated")));
    }

    /** "show ..." is controlled English, except the SQL statement SHOW INDEX(ES). */
    private static boolean isEnglishCommand(String input) {
        return input.regionMatches(true, 0, "show", 0, 4)
                && (input.length() == 4 || Character.isWhitespace(input.charAt(4)))
                && !SHOW_INDEXES.matcher(input).matches();
    }

    /** Handles backslash commands; only {@code \timing [on|off]} is supported. */
    private void handleMetaCommand(String line, PrintStream output) {
        String[] parts = line.replaceFirst(";+$", "").trim().split("\\s+");
        if (!parts[0].equalsIgnoreCase("\\timing") || parts.length > 2) {
            output.println("Invalid command " + parts[0] + ".");
            return;
        }
        if (parts.length == 1) {
            timing = !timing;
        } else if (parts[1].equalsIgnoreCase("on")) {
            timing = true;
        } else if (parts[1].equalsIgnoreCase("off")) {
            timing = false;
        } else {
            output.println("\\timing: unrecognized value \"" + parts[1] + "\": Boolean expected");
            return;
        }
        output.println(timing ? "Timing is on." : "Timing is off.");
    }
}
