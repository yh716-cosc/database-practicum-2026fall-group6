package minidb.plan;

import java.util.List;

/** Logical steps produced by the planner and compiled by the executor. */
public interface LogicalOperator {
    record Scan(String tableName) implements LogicalOperator {}

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
