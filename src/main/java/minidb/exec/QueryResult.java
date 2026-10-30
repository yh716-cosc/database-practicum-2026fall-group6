package minidb.exec;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import minidb.storage.Row;
import minidb.types.DataType;
import minidb.types.Value;

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
            out.println(format(row));
        }
        out.println("(" + rows.size() + " rows)");
    }

    private static String format(Row row) {
        return row.values().stream()
                .map(QueryResult::formatValue)
                .collect(Collectors.joining(" | "));
    }

    private static String formatValue(Value value) {
        if (value.isNull()) return "NULL";
        if (value.type() == DataType.STRING) {
            return "'" + value.raw() + "'";
        }
        return String.valueOf(value.raw());
    }
}
