package minidb.exec;

import minidb.storage.Row;
import minidb.storage.RowCursor;
import minidb.storage.TableStore;

/** A single-use streaming scan. Owns and closes its storage cursor. */
public final class TableScanOperator implements Operator {
    private enum State { NEW, OPEN, EOF, CLOSED }

    private final TableStore store;
    private final String tableName;
    private RowCursor cursor;
    private State state = State.NEW;

    public TableScanOperator(TableStore store, String tableName) {
        this.store = store;
        this.tableName = tableName;
    }

    @Override
    public void open() {
        if (state != State.NEW) {
            throw new IllegalStateException("A table scan can only be opened once");
        }
        try {
            cursor = store.scan(tableName);
            cursor.open();
            state = State.OPEN;
        } catch (RuntimeException failure) {
            closeAfterFailure(failure);
            throw failure;
        }
    }

    @Override
    public Row next() {
        if (state == State.EOF) {
            return null;
        }
        if (state != State.OPEN) {
            throw new IllegalStateException("Table scan must be open before reading");
        }
        try {
            Row row = cursor.next();
            if (row == null) {
                close();
                state = State.EOF;
            }
            return row;
        } catch (RuntimeException failure) {
            closeAfterFailure(failure);
            throw failure;
        }
    }

    @Override
    public void close() {
        state = State.CLOSED;
        // Detach before closing so cleanup is attempted only once, even on failure.
        RowCursor owned = cursor;
        cursor = null;
        if (owned != null) {
            owned.close();
        }
    }

    private void closeAfterFailure(RuntimeException failure) {
        try {
            close();
        } catch (RuntimeException closeFailure) {
            if (closeFailure != failure) {
                failure.addSuppressed(closeFailure);
            }
        }
    }
}
