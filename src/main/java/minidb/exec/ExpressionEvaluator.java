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
        return truth(predicate, row) == TruthValue.TRUE;
    }

    /** Retains UNKNOWN through boolean expressions instead of treating it as FALSE. */
    public TruthValue truth(BoundExpression.Predicate predicate, Row row) {
        if (predicate instanceof BoundExpression.IsNull check) {
            return TruthValue.of(evaluate(check.operand(), row).isNull() != check.negated());
        }
        if (predicate instanceof BoundExpression.Constant constant) {
            return TruthValue.of(constant.value());
        }
        if (predicate instanceof BoundExpression.And and) {
            TruthValue left = truth(and.left(), row);
            return left == TruthValue.FALSE ? left : left.and(truth(and.right(), row));
        }
        if (predicate instanceof BoundExpression.Or or) {
            TruthValue left = truth(or.left(), row);
            return left == TruthValue.TRUE ? left : left.or(truth(or.right(), row));
        }
        if (predicate instanceof BoundExpression.Not not) {
            return truth(not.child(), row).not();
        }
        if (predicate instanceof BoundExpression.Comparison comparison) {
            Value left = evaluate(comparison.left(), row);
            Value right = evaluate(comparison.right(), row);
            if (left.type() != right.type()) {
                throw new IllegalArgumentException("Comparison requires matching types: "
                        + left.type() + " and " + right.type());
            }
            if (left.isNull() || right.isNull()) return TruthValue.UNKNOWN;
            if (left.type() == DataType.INTEGER) {
                int order = Integer.compare((Integer) left.raw(), (Integer) right.raw());
                return TruthValue.of(switch (comparison.op()) {
                    case EQ -> order == 0;
                    case NE -> order != 0;
                    case LT -> order < 0;
                    case LE -> order <= 0;
                    case GT -> order > 0;
                    case GE -> order >= 0;
                });
            }
            if (left.type() == DataType.STRING) {
                boolean equal = ((String) left.raw()).equals((String) right.raw());
                return TruthValue.of(switch (comparison.op()) {
                    case EQ -> equal;
                    case NE -> !equal;
                    default -> throw new IllegalArgumentException(
                            "STRING comparison only supports EQ and NE: " + comparison.op());
                });
            }
            throw new IllegalArgumentException("Unsupported comparison type: " + left.type());
        }
        throw new IllegalArgumentException("Unsupported predicate: " + predicate);
    }
}
