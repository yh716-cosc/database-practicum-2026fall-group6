package minidb.exec;

import java.util.List;

import minidb.storage.Row;

/** Skeleton for selecting columns in order, including repeated selections. */
public final class ProjectOperator implements Operator {
    private final Operator child;
    private final List<Integer> columnIndexes;

    public ProjectOperator(Operator child, List<Integer> columnIndexes) {
        this.child = child;
        this.columnIndexes = List.copyOf(columnIndexes);
    }

    @Override
    public void open() {
        // TODO: Open the child and enforce a single execution lifecycle.
        throw new UnsupportedOperationException("Projection is not implemented");
    }

    @Override
    public Row next() {
        // TODO: Read one child row and select values using columnIndexes.
        throw new UnsupportedOperationException("Projection is not implemented");
    }

    @Override
    public void close() {
        // TODO: Close the child; repeated close calls must be safe.
        throw new UnsupportedOperationException("Projection is not implemented");
    }
}
