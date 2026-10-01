package minidb.plan;

import minidb.sql.ast.Expression.ComparisonOp;
import minidb.types.DataType;
import minidb.types.Value;

/** Expressions after column binding. The planner must validate operand types. */
public interface BoundExpression {
    interface Scalar extends BoundExpression {
        DataType type();
    }

    interface Predicate extends BoundExpression {}

    /** Index refers to the input row; type comes from the catalog. */
    record Column(int index, DataType type) implements Scalar {}

    record Literal(Value value) implements Scalar {
        @Override
        public DataType type() {
            return value.type();
        }
    }

    /** INTEGER supports all operators; STRING supports EQ and NE only. */
    record Comparison(Scalar left, ComparisonOp op, Scalar right) implements Predicate {}

    record And(Predicate left, Predicate right) implements Predicate {}

    record Or(Predicate left, Predicate right) implements Predicate {}

    record Not(Predicate child) implements Predicate {}
}
