package minidb.exec;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import minidb.storage.Row;
import minidb.types.DataType;
import minidb.types.Value;

public final class QueryResult {
    private final List<Row> rows = new ArrayList<>();
    private final List<String> columnNames;
    private String message;

    public QueryResult() {
        this(List.of());
    }

    public QueryResult(List<String> columnNames) {
        this.columnNames = List.copyOf(columnNames);
    }

    /** Result of a command such as CREATE INDEX: no rows, just a status line. */
    public static QueryResult message(String message) {
        QueryResult result = new QueryResult();
        result.message = message;
        return result;
    }

    /** The status line of a command, or null for a query. */
    public String message() {
        return message;
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
        if (message != null) {
            out.println(message);
            return;
        }
        if (!columnNames.isEmpty()) {
            out.println(String.join(" | ", columnNames.stream().map(QueryResult::display).toList()));
            out.println(String.join("-+-", columnNames.stream()
                    .map(name -> "-".repeat(display(name).length())).toList()));
        }
        for (Row row : rows) {
            out.println(String.join(" | ", row.values().stream()
                    .map(QueryResult::formatValue).toList()));
        }
        out.println("(" + rows.size() + " rows)");
    }

    private static String formatValue(Value value) {
        if (value.isNull()) return "NULL";
        // Quote text that could be confused with NULL or with an already quoted string.
        if (value.type() == DataType.STRING) {
            String text = (String) value.raw();
            if (text.equals("NULL") || text.startsWith("'")) {
                return "'" + display(text.replace("'", "''")) + "'";
            }
        }
        if (value.raw() instanceof java.math.BigDecimal decimal) return decimal.toPlainString();
        return display(String.valueOf(value.raw()));
    }

    /** Keep one physical line per row and make empty strings visible. */
    private static String display(String value) {
        if (value.isEmpty()) return "\"\"";
        return value.replace("\\", "\\\\").replace("\r", "\\r")
                .replace("\n", "\\n").replace("\t", "\\t").replace("|", "\\|");
    }
}
