package minidb.exec;

import minidb.plan.LogicalOperator;
import minidb.storage.TableStore;

public final class Executor {
    private final TableStore store;

    public Executor(TableStore store) {
        this.store = store;
    }

    /** Builds a fresh operator tree without opening cursors or reading rows. */
    public Operator compile(LogicalOperator plan) {
        if (plan instanceof LogicalOperator.Join join) {
            if (join.type() == minidb.sql.ast.Join.Type.RIGHT) {
                return new NestedLoopJoinOperator(compile(join.right()), () -> compile(join.left()),
                        join.condition(), true, true, join.leftTypes());
            }
            return new NestedLoopJoinOperator(compile(join.left()), () -> compile(join.right()),
                    join.condition(), join.type() == minidb.sql.ast.Join.Type.LEFT, false, join.rightTypes());
        }
        if (plan instanceof LogicalOperator.Sort sort) {
            return new SortOperator(compile(sort.child()), sort.keys());
        }
        if (plan instanceof LogicalOperator.Limit limit) {
            return new LimitOperator(compile(limit.child()), limit.count());
        }
        if (plan instanceof LogicalOperator.Scan scan) {
            return new TableScanOperator(store, scan.tableName());
        }
        if (plan instanceof LogicalOperator.Filter filter) {
            return new FilterOperator(compile(filter.child()), filter.predicate(), new ExpressionEvaluator());
        }
        if (plan instanceof LogicalOperator.Project project) {
            return new ProjectOperator(compile(project.child()), project.columnIndexes());
        }
        throw new IllegalArgumentException("Unsupported logical plan: " + plan);
    }
}
