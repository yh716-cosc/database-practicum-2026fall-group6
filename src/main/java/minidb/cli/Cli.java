package minidb.cli;

import java.io.PrintStream;
import java.util.Locale;
import java.util.Scanner;

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
    private final Engine engine;
    private final EnglishCommandTranslator englishTranslator = new EnglishCommandTranslator();

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
            try {
                if (line.startsWith(".")) {
                    statisticsCommand(line, output);
                    continue;
                }
                String sql = isEnglishCommand(line) ? englishTranslator.translate(line) : line;
                QueryResult result = engine.execute(sql);
                result.printTo(output);
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

    private static boolean isEnglishCommand(String input) {
        return input.regionMatches(true, 0, "show", 0, 4)
                && (input.length() == 4 || Character.isWhitespace(input.charAt(4)));
    }
}
