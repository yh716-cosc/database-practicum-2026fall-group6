package minidb.exec;

import minidb.plan.BoundExpression;
import minidb.storage.Row;

/** A single-use streaming filter that owns its child operator. */
public final class FilterOperator implements Operator {
    private enum State { NEW, OPEN, EOF, CLOSED }

    private State state = State.NEW;
    private boolean childCloseAttempted;
    private final Operator child;
    private final BoundExpression.Predicate predicate;
    private final ExpressionEvaluator evaluator;

    public FilterOperator(Operator child, BoundExpression.Predicate predicate,
            ExpressionEvaluator evaluator) {
        this.child = child;
        this.predicate = predicate;
        this.evaluator = evaluator;
    }

    @Override
    public void open() {
        if (state != State.NEW) {
            throw new IllegalStateException("A filter can only be opened once");
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
            throw new IllegalStateException("Filter must be open before reading");
        }
        try {
            Row row;
            while ((row = child.next()) != null) {
                if (evaluator.test(predicate, row)) {
                    return row;
                }
            }
            close();
            state = State.EOF;
            return null;
        } catch (RuntimeException failure) {
            closeAfterFailure(failure);
            throw failure;
        }
    }

    @Override
    public void close() {
        state = State.CLOSED;
        if (!childCloseAttempted) {
            // Mark first so a failed close is not retried by exception cleanup.
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
