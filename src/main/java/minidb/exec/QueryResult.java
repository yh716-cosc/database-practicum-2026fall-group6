package minidb.exec;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import minidb.storage.Row;

public final class QueryResult {
    private final List<Row> rows = new ArrayList<>();

    public void add(Row row) {
        rows.add(row);
    }

    public List<Row> rows() {
        return List.copyOf(rows);
    }

    public void printTo(PrintStream out) {
        for (Row row : rows) {
            out.println(row.values());
        }
        out.println("(" + rows.size() + " rows)");
    }
}
