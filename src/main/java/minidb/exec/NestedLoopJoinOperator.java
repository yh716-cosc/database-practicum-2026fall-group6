package minidb.exec;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import minidb.plan.BoundExpression;
import minidb.storage.Row;
import minidb.types.Value;

/** Streaming tuple nested-loop join. Recreates the right subtree for each left row. */
public final class NestedLoopJoinOperator implements Operator {
    private enum State { NEW, OPEN, EOF, CLOSED }
    private final Operator left;
    private final Supplier<Operator> rightFactory;
    private final BoundExpression.Predicate condition;
    private final ExpressionEvaluator evaluator = new ExpressionEvaluator();
    private Operator right;
    private Row leftRow;
    private State state = State.NEW;
    private boolean ownsLeft;
    private final boolean preserveOuter;
    private final boolean outerIsRight;
    private final Row nullInner;
    private boolean matched;

    public NestedLoopJoinOperator(Operator left, Supplier<Operator> rightFactory,
                                  BoundExpression.Predicate condition) {
        this(left, rightFactory, condition, false, false, List.of());
    }

    /** left is the physical outer input; outerIsRight restores logical SQL column order. */
    public NestedLoopJoinOperator(Operator left, Supplier<Operator> rightFactory,
                                  BoundExpression.Predicate condition, boolean preserveOuter,
                                  boolean outerIsRight, List<minidb.types.DataType> innerTypes) {
        this.left = Objects.requireNonNull(left);
        this.rightFactory = Objects.requireNonNull(rightFactory);
        this.condition = Objects.requireNonNull(condition);
        this.preserveOuter = preserveOuter;
        this.outerIsRight = outerIsRight;
        this.nullInner = new Row(innerTypes.stream().map(Value::nullValue).toList());
    }

    @Override public void open() {
        if (state != State.NEW) throw new IllegalStateException("Join can only be opened once");
        try {
            ownsLeft = true;
            left.open();
            state = State.OPEN;
        } catch (RuntimeException failure) {
            fail(failure);
            throw failure;
        }
    }

    @Override public Row next() {
        if (state == State.EOF) return null;
        if (state != State.OPEN) throw new IllegalStateException("Join must be open before reading");
        try {
            while (true) {
                if (leftRow == null) {
                    leftRow = left.next();
                    if (leftRow == null) {
                        close();
                        state = State.EOF;
                        return null;
                    }
                    right = Objects.requireNonNull(rightFactory.get(), "Right factory returned null");
                    right.open();
                    matched = false;
                }
                Row rightRow = right.next();
                if (rightRow == null) {
                    closeRight();
                    Row unmatched = preserveOuter && !matched ? combine(leftRow, nullInner) : null;
                    leftRow = null;
                    if (unmatched != null) return unmatched;
                    continue;
                }
                Row combined = combine(leftRow, rightRow);
                if (evaluator.test(condition, combined)) {
                    matched = true;
                    return combined;
                }
            }
        } catch (RuntimeException failure) {
            fail(failure);
            throw failure;
        }
    }

    private Row combine(Row outer, Row inner) {
        List<Value> values = new ArrayList<>(outer.values().size() + inner.values().size());
        values.addAll((outerIsRight ? inner : outer).values());
        values.addAll((outerIsRight ? outer : inner).values());
        return new Row(List.copyOf(values));
    }

    private void closeRight() {
        Operator owned = right;
        right = null;
        if (owned != null) owned.close();
    }

    @Override public void close() {
        state = State.CLOSED;
        leftRow = null;
        RuntimeException failure = null;
        try { closeRight(); } catch (RuntimeException e) { failure = e; }
        if (ownsLeft) {
            ownsLeft = false;
            try { left.close(); } catch (RuntimeException e) {
                if (failure == null) failure = e;
                else if (failure != e) failure.addSuppressed(e);
            }
        }
        if (failure != null) throw failure;
    }

    private void fail(RuntimeException failure) {
        try { close(); } catch (RuntimeException cleanup) {
            if (cleanup != failure) failure.addSuppressed(cleanup);
        }
    }
}
