package minidb.cli;

import java.io.PrintStream;
import java.util.Scanner;

import minidb.Engine;
import minidb.command.EnglishCommandException;
import minidb.command.EnglishCommandTranslator;
import minidb.exec.QueryResult;
import minidb.sql.SqlParseException;
import minidb.plan.PlanningException;
import minidb.storage.StorageException;

/** Reads one SQL statement per line. The database stays read-only. */
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
        output.println("minidb skeleton. Type a query, or quit.");
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

    private static boolean isEnglishCommand(String input) {
        return input.regionMatches(true, 0, "show", 0, 4)
                && (input.length() == 4 || Character.isWhitespace(input.charAt(4)));
    }
}
