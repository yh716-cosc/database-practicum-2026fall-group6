package minidb.exec;

import minidb.plan.BoundExpression;
import minidb.storage.Row;

/** Skeleton for streaming rows that satisfy a bound predicate. */
public final class FilterOperator implements Operator {
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
        // TODO: Open the child and enforce a single execution lifecycle.
        throw new UnsupportedOperationException("Filtering is not implemented");
    }

    @Override
    public Row next() {
        // TODO: Pull rows until evaluator.test(predicate, row) succeeds or EOF.
        // Return the original matching row without changing its column layout.
        throw new UnsupportedOperationException("Filtering is not implemented");
    }

    @Override
    public void close() {
        // TODO: Close the child; repeated close calls must be safe.
        throw new UnsupportedOperationException("Filtering is not implemented");
    }
}
