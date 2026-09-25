package minidb.cli;

import java.util.Scanner;

import minidb.Engine;
import minidb.exec.QueryResult;

/** Reads one SQL statement per line. The database stays read-only. */
public final class Cli {
    private final Engine engine;

    public Cli(Engine engine) {
        this.engine = engine;
    }

    public void run() {
        System.out.println("minidb skeleton. Type a query, or quit.");
        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.print("minidb> ");
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
                result.printTo(System.out);
            } catch (UnsupportedOperationException ex) {
                System.out.println(ex.getMessage());
            }
        }
    }
}
