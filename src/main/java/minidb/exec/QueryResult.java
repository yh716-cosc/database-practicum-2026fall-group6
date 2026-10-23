package minidb.exec;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import minidb.storage.Row;

public final class QueryResult {
    private final List<Row> rows = new ArrayList<>();
    private final List<String> columnNames;

    public QueryResult() {
        this(List.of());
    }

    public QueryResult(List<String> columnNames) {
        this.columnNames = List.copyOf(columnNames);
    }

    public List<String> columnNames() {
        return columnNames;
    }

    public void add(Row row) {
        rows.add(row);
    }

    public List<Row> rows() {
        return List.copyOf(rows);
    }

    public void printTo(PrintStream out) {
        if (!columnNames.isEmpty()) {
            out.println(String.join(" | ", columnNames.stream().map(QueryResult::display).toList()));
            out.println(String.join("-+-", columnNames.stream()
                    .map(name -> "-".repeat(display(name).length())).toList()));
        }
        for (Row row : rows) {
            out.println(String.join(" | ", row.values().stream()
                    .map(value -> display(String.valueOf(value.raw()))).toList()));
        }
        out.println("(" + rows.size() + " rows)");
    }

    /** Keep one physical line per row and make empty strings visible. */
    private static String display(String value) {
        if (value.isEmpty()) return "\"\"";
        return value.replace("\\", "\\\\").replace("\r", "\\r")
                .replace("\n", "\\n").replace("\t", "\\t").replace("|", "\\|");
    }
}
