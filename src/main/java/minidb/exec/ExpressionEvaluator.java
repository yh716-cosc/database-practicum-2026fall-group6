package minidb.exec;

import minidb.plan.BoundExpression;
import minidb.storage.Row;
import minidb.types.DataType;
import minidb.types.Value;

/** Evaluates expressions already bound and type-checked by the planner. */
public final class ExpressionEvaluator {
    public Value evaluate(BoundExpression.Scalar expression, Row row) {
        if (expression instanceof BoundExpression.Literal literal) {
            return literal.value();
        }
        if (expression instanceof BoundExpression.Column column) {
            return row.values().get(column.index());
        }
        throw new IllegalArgumentException("Unsupported scalar expression: " + expression);
    }

    public boolean test(BoundExpression.Predicate predicate, Row row) {
        if (predicate instanceof BoundExpression.And and) {
            return test(and.left(), row) && test(and.right(), row);
        }
        if (predicate instanceof BoundExpression.Or or) {
            return test(or.left(), row) || test(or.right(), row);
        }
        if (predicate instanceof BoundExpression.Not not) {
            return !test(not.child(), row);
        }
        if (predicate instanceof BoundExpression.Comparison comparison) {
            Value left = evaluate(comparison.left(), row);
            Value right = evaluate(comparison.right(), row);
            if (left.type() != right.type()) {
                throw new IllegalArgumentException("Comparison requires matching types: "
                        + left.type() + " and " + right.type());
            }
            if (left.type() == DataType.INTEGER) {
                int order = Integer.compare((Integer) left.raw(), (Integer) right.raw());
                return switch (comparison.op()) {
                    case EQ -> order == 0;
                    case NE -> order != 0;
                    case LT -> order < 0;
                    case LE -> order <= 0;
                    case GT -> order > 0;
                    case GE -> order >= 0;
                };
            }
            if (left.type() == DataType.STRING) {
                boolean equal = ((String) left.raw()).equals((String) right.raw());
                return switch (comparison.op()) {
                    case EQ -> equal;
                    case NE -> !equal;
                    default -> throw new IllegalArgumentException(
                            "STRING comparison only supports EQ and NE: " + comparison.op());
                };
            }
            throw new IllegalArgumentException("Unsupported comparison type: " + left.type());
        }
        throw new IllegalArgumentException("Unsupported predicate: " + predicate);
    }
}
