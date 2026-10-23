package minidb.cli;

import java.io.PrintStream;
import java.util.Scanner;

import minidb.Engine;
import minidb.exec.QueryResult;
import minidb.sql.SqlParseException;
import minidb.plan.PlanningException;
import minidb.storage.StorageException;

/** Reads one SQL statement per line. The database stays read-only. */
public final class Cli {
    private final Engine engine;

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
                QueryResult result = engine.execute(line);
                result.printTo(output);
            } catch (SqlParseException | PlanningException | StorageException ex) {
                output.println(ex.getMessage());
            } catch (UnsupportedOperationException ex) {
                output.println(ex.getMessage());
            }
        }
    }
}
