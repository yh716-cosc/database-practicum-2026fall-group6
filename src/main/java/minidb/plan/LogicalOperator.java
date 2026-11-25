package minidb.plan;

import java.util.List;

/** Logical steps produced by the planner and compiled by the executor. */
public interface LogicalOperator {
    record Scan(String tableName) implements LogicalOperator {}

    /** Rows of the table whose indexed column is in range, in scan order. Same layout as Scan. */
    record IndexScan(String tableName, String column, minidb.index.KeyRange range)
            implements LogicalOperator {}
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

    /**
     * One aggregate computed per group. {@code argumentIndex} refers to the aggregate's input
     * layout and is -1 for COUNT(*); {@code argumentType} is null for COUNT(*).
     * {@code label} is the display name, such as {@code COUNT(*)} or {@code AVG(s.score)}.
     */
    record AggregateCall(minidb.sql.ast.Expression.AggregateFunction function, int argumentIndex,
                         minidb.types.DataType argumentType, String label) {
        public AggregateCall {
            java.util.Objects.requireNonNull(function);
            java.util.Objects.requireNonNull(label);
            if ((argumentIndex < 0) != (argumentType == null)
                    || (argumentIndex < 0 && function != minidb.sql.ast.Expression.AggregateFunction.COUNT)) {
                throw new IllegalArgumentException("Only COUNT(*) has no argument column");
            }
        }

        /** COUNT and SUM return BIGINT, AVG returns DECIMAL, MIN and MAX keep the input type. */
        public minidb.types.DataType resultType() {
            return switch (function) {
                case COUNT, SUM -> minidb.types.DataType.BIGINT;
                case AVG -> minidb.types.DataType.DECIMAL;
                case MIN, MAX -> argumentType;
            };
        }
    }

    /**
     * Groups child rows by the values at {@code groupIndexes} (typed NULLs form one group) and
     * computes {@code aggregates} per group. With no group indexes it is a global aggregate that
     * emits exactly one row, even for empty input. Output layout: group key columns in
     * {@code groupIndexes} order, then one column per aggregate.
     */
    record Aggregate(LogicalOperator child, List<Integer> groupIndexes, List<AggregateCall> aggregates)
            implements LogicalOperator {
        public Aggregate {
            java.util.Objects.requireNonNull(child);
            groupIndexes = List.copyOf(groupIndexes);
            aggregates = List.copyOf(aggregates);
            if (groupIndexes.isEmpty() && aggregates.isEmpty()) {
                throw new IllegalArgumentException("Aggregate requires a group key or an aggregate");
            }
        }
    }

    /** Column indexes refer to the child operator's output layout. */
    record Project(LogicalOperator child, List<Integer> columnIndexes)
            implements LogicalOperator {}
}
