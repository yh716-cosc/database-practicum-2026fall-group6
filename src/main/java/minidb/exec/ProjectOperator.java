package minidb.exec;

import java.util.ArrayList;
import java.util.List;

import minidb.storage.Row;
import minidb.types.Value;

/** Single-use projection that owns its child and preserves selection order. */
public final class ProjectOperator implements Operator {
    private enum State { NEW, OPEN, EOF, CLOSED }

    private State state = State.NEW;
    private boolean childCloseAttempted;
    private final Operator child;
    private final List<Integer> columnIndexes;

    public ProjectOperator(Operator child, List<Integer> columnIndexes) {
        this.child = child;
        this.columnIndexes = List.copyOf(columnIndexes);
    }

    @Override
    public void open() {
        if (state != State.NEW) {
            throw new IllegalStateException("A projection can only be opened once");
        }
        try {
            child.open();
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
            throw new IllegalStateException("Projection must be open before reading");
        }
        try {
            Row input = child.next();
            if (input == null) {
                close();
                state = State.EOF;
                return null;
            }
            List<Value> selected = new ArrayList<>(columnIndexes.size());
            for (int index : columnIndexes) {
                selected.add(input.values().get(index));
            }
            return new Row(List.copyOf(selected));
        } catch (RuntimeException failure) {
            closeAfterFailure(failure);
            throw failure;
        }
    }

    @Override
    public void close() {
        state = State.CLOSED;
        if (!childCloseAttempted) {
            childCloseAttempted = true;
            child.close();
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
