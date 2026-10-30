package minidb.exec;

import minidb.storage.Row;

/** Shared single-use lifecycle for operators that own one child. */
abstract class UnaryOperator implements Operator {
    private enum State { NEW, OPEN, EOF, CLOSED }
    private State state = State.NEW;
    private boolean closeAttempted;
    protected final Operator child;

    UnaryOperator(Operator child) { this.child = child; }

    @Override public final void open() {
        if (state != State.NEW) throw new IllegalStateException("Operator can only be opened once");
        try {
            child.open();
            state = State.OPEN;
        } catch (RuntimeException failure) {
            cleanup(failure);
            throw failure;
        }
    }

    @Override public final Row next() {
        if (state == State.EOF) return null;
        if (state != State.OPEN) throw new IllegalStateException("Operator must be open before reading");
        try {
            Row row = readNext();
            if (row == null) {
                close();
                state = State.EOF;
            }
            return row;
        } catch (RuntimeException failure) {
            cleanup(failure);
            throw failure;
        }
    }

    protected abstract Row readNext();
    protected void release() {}

    @Override public final void close() {
        state = State.CLOSED;
        release();
        if (!closeAttempted) {
            closeAttempted = true;
            child.close();
        }
    }

    private void cleanup(RuntimeException failure) {
        try { close(); }
        catch (RuntimeException closeFailure) {
            if (closeFailure != failure) failure.addSuppressed(closeFailure);
        }
    }
}
