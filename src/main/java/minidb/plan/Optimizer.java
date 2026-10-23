package minidb.plan;

import java.util.List;
import minidb.exec.ExpressionEvaluator;
import minidb.plan.BoundExpression.*;

/** Pure rule-based rewrites of fully bound, type-checked plans. No storage reads. */
public final class Optimizer {
    public LogicalOperator optimize(LogicalOperator plan) {
        if (plan instanceof LogicalOperator.Limit limit) {
            LogicalOperator child = optimize(limit.child());
            if (child instanceof LogicalOperator.Limit inner) {
                return new LogicalOperator.Limit(inner.child(), Math.min(limit.count(), inner.count()));
            }
            return new LogicalOperator.Limit(child, limit.count());
        }
        if (plan instanceof LogicalOperator.Sort sort) {
            LogicalOperator child = optimize(sort.child());
            if (child instanceof LogicalOperator.Sort inner && inner.keys().equals(sort.keys())) {
                child = inner.child();
            }
            return new LogicalOperator.Sort(child, sort.keys());
        }
        if (plan instanceof LogicalOperator.Scan) {
            return plan;
        }
        if (plan instanceof LogicalOperator.Filter filter) {
            LogicalOperator child = optimize(filter.child());
            Predicate predicate = simplify(filter.predicate());
            if (predicate instanceof Constant constant && constant.value()) {
                return child;
            }
            // Even a false predicate must scan: malformed CSV remains an error.
            return new LogicalOperator.Filter(child, predicate);
        }
        if (plan instanceof LogicalOperator.Project project) {
            LogicalOperator child = optimize(project.child());
            List<Integer> indexes = List.copyOf(project.columnIndexes());
            if (child instanceof LogicalOperator.Project inner) {
                indexes = indexes.stream().map(inner.columnIndexes()::get).toList();
                child = inner.child();
            }
            return new LogicalOperator.Project(child, indexes);
        }
        throw new IllegalArgumentException("Unsupported logical plan: " + plan);
    }

    private Predicate simplify(Predicate predicate) {
        if (predicate instanceof Constant) {
            return predicate;
        }
        if (predicate instanceof Comparison comparison) {
            if (comparison.left() instanceof Literal && comparison.right() instanceof Literal) {
                return new Constant(new ExpressionEvaluator().test(comparison, null));
            }
            return predicate;
        }
        if (predicate instanceof Not not) {
            Predicate child = simplify(not.child());
            if (child instanceof Constant constant) {
                return new Constant(!constant.value());
            }
            if (child instanceof Not nested) {
                return nested.child();
            }
            return new Not(child);
        }
        if (predicate instanceof And and) {
            Predicate left = simplify(and.left());
            Predicate right = simplify(and.right());
            if (left instanceof Constant constant) {
                return constant.value() ? right : left;
            }
            if (right instanceof Constant constant) {
                return constant.value() ? left : right;
            }
            return new And(left, right);
        }
        if (predicate instanceof Or or) {
            Predicate left = simplify(or.left());
            Predicate right = simplify(or.right());
            if (left instanceof Constant constant) {
                return constant.value() ? left : right;
            }
            if (right instanceof Constant constant) {
                return constant.value() ? right : left;
            }
            return new Or(left, right);
        }
        throw new IllegalArgumentException("Unsupported predicate: " + predicate);
    }
}
