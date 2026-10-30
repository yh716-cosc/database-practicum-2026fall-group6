package minidb.exec;

import minidb.storage.Row;

/** Streams at most count rows; never asks the child for an extra row. */
public final class LimitOperator extends UnaryOperator {
    private final int count;
    private int emitted;

    public LimitOperator(Operator child, int count) {
        super(child);
        if (count < 0) throw new IllegalArgumentException("Negative LIMIT");
        this.count = count;
    }

    @Override protected Row readNext() {
        if (emitted == count) return null;
        Row row = child.next();
        if (row != null) emitted++;
        return row;
    }
}
