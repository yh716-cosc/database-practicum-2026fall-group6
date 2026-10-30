package minidb.exec;

import java.util.ArrayList;
import java.util.List;
import minidb.plan.LogicalOperator.SortKey;
import minidb.storage.Row;
import minidb.types.DataType;
import minidb.types.Value;

/** Blocking in-memory stable sort. Materialization starts on the first next(). */
public final class SortOperator extends UnaryOperator {
    private final List<SortKey> keys;
    private final List<Row> rows = new ArrayList<>();
    private boolean loaded;
    private int position;

    public SortOperator(Operator child, List<SortKey> keys) {
        super(child);
        this.keys = List.copyOf(keys);
    }

    @Override protected Row readNext() {
        if (!loaded) {
            Row row;
            while ((row = child.next()) != null) rows.add(row);
            rows.sort(this::compare);
            loaded = true;
        }
        return position < rows.size() ? rows.get(position++) : null;
    }

    private int compare(Row left, Row right) {
        for (SortKey key : keys) {
            Value a = left.values().get(key.index());
            Value b = right.values().get(key.index());
            if (a.type() != b.type()) throw new IllegalArgumentException("Sort key types differ");
            int order;
            // Explicit policy: NULLS LAST for ASC, NULLS FIRST for DESC.
            if (a.isNull() || b.isNull()) {
                order = a.isNull() ? (b.isNull() ? 0 : 1) : -1;
            } else if (a.type() == DataType.INTEGER) {
                order = Integer.compare((Integer) a.raw(), (Integer) b.raw());
            } else if (a.type() == DataType.STRING) {
                order = ((String) a.raw()).compareTo((String) b.raw());
            } else {
                throw new IllegalArgumentException("Unsupported sort key type: " + a.type());
            }
            if (order != 0) return key.ascending() ? order : -order;
        }
        return 0;
    }

    @Override protected void release() { rows.clear(); }
}
