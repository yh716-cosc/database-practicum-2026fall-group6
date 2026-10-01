package minidb.exec;

import minidb.storage.Row;
import minidb.storage.TableStore;

/** Skeleton for an operator that delegates to a storage RowCursor. */
public final class TableScanOperator implements Operator {
    private final TableStore store;
    private final String tableName;

    public TableScanOperator(TableStore store, String tableName) {
        this.store = store;
        this.tableName = tableName;
    }

    @Override
    public void open() {
        // TODO: Obtain and open a cursor from store.scan(tableName).
        throw new UnsupportedOperationException("Table scan is not implemented");
    }

    @Override
    public Row next() {
        // TODO: Delegate to the cursor, returning null at EOF.
        throw new UnsupportedOperationException("Table scan is not implemented");
    }

    @Override
    public void close() {
        // TODO: Close the cursor safely, including after a failed open.
        throw new UnsupportedOperationException("Table scan is not implemented");
    }
}
