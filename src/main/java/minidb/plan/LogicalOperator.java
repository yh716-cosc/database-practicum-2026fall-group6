package minidb.plan;

import java.util.List;

/** Logical steps produced by the planner and compiled by the executor. */
public interface LogicalOperator {
    record Scan(String tableName) implements LogicalOperator {}

    record Filter(LogicalOperator child, BoundExpression.Predicate predicate)
            implements LogicalOperator {}

    /** Column indexes refer to the child operator's output layout. */
    record Project(LogicalOperator child, List<Integer> columnIndexes)
            implements LogicalOperator {}
}
