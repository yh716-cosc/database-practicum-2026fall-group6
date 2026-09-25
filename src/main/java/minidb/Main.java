package minidb;

import java.nio.file.Path;

import minidb.cli.Cli;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: minidb <data-directory>");
            System.exit(1);
        }
        Path dataDir = Path.of(args[0]);
        new Cli(new Engine(dataDir)).run();
    }
}
