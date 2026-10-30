package minidb.plan;

import java.util.List;

/** Logical steps produced by the planner and compiled by the executor. */
public interface LogicalOperator {
    record Scan(String tableName) implements LogicalOperator {}
    record Join(LogicalOperator left, LogicalOperator right, BoundExpression.Predicate condition,
                minidb.sql.ast.Join.Type type, List<minidb.types.DataType> leftTypes,
                List<minidb.types.DataType> rightTypes) implements LogicalOperator {
        public Join {
            java.util.Objects.requireNonNull(type);
            leftTypes = List.copyOf(leftTypes);
            rightTypes = List.copyOf(rightTypes);
            if ((type == minidb.sql.ast.Join.Type.LEFT && rightTypes.isEmpty())
                    || (type == minidb.sql.ast.Join.Type.RIGHT && leftTypes.isEmpty())) {
                throw new IllegalArgumentException("Outer join requires the nullable input's column types");
            }
        }
        public Join(LogicalOperator left, LogicalOperator right, BoundExpression.Predicate condition) {
            this(left, right, condition, minidb.sql.ast.Join.Type.INNER, List.of(), List.of());
        }
    }

    record SortKey(int index, boolean ascending) {}

    record Sort(LogicalOperator child, List<SortKey> keys) implements LogicalOperator {
        public Sort { keys = List.copyOf(keys); }
    }

    record Limit(LogicalOperator child, int count) implements LogicalOperator {
        public Limit {
            if (count < 0) throw new IllegalArgumentException("Negative LIMIT");
        }
    }

    record Filter(LogicalOperator child, BoundExpression.Predicate predicate)
            implements LogicalOperator {}

    /** Column indexes refer to the child operator's output layout. */
    record Project(LogicalOperator child, List<Integer> columnIndexes)
            implements LogicalOperator {}
}
