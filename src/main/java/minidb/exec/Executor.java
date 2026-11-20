package minidb.exec;

import minidb.plan.CostBasedOptimizer.JoinChoice;
import minidb.plan.LogicalOperator;
import minidb.storage.TableStore;

public final class Executor {
    private final TableStore store;

    public Executor(TableStore store) {
        this.store = store;
    }

    /** Builds a fresh operator tree without opening cursors or reading rows. */
    public Operator compile(LogicalOperator plan) {
        return compile(plan, null);
    }

    /** Applies the physical choice to the selected join in this logical tree. */
    public Operator compile(LogicalOperator plan, JoinChoice choice) {
        if (plan instanceof LogicalOperator.Join join) {
            if (join.type() == minidb.sql.ast.Join.Type.RIGHT) {
                return new NestedLoopJoinOperator(compile(join.right(), choice), () -> compile(join.left(), choice),
                        join.condition(), true, true, join.leftTypes());
            }
            if (join.type() == minidb.sql.ast.Join.Type.INNER
                    && choice != null && choice.join() == join && choice.driveFromRight()) {
                return new NestedLoopJoinOperator(compile(join.right(), choice), () -> compile(join.left(), choice),
                        join.condition(), false, true, join.leftTypes());
            }
            return new NestedLoopJoinOperator(compile(join.left(), choice), () -> compile(join.right(), choice),
                    join.condition(), join.type() == minidb.sql.ast.Join.Type.LEFT, false, join.rightTypes());
        }
        if (plan instanceof LogicalOperator.Sort sort) {
            return new SortOperator(compile(sort.child(), choice), sort.keys());
        }
        if (plan instanceof LogicalOperator.Limit limit) {
            return new LimitOperator(compile(limit.child(), choice), limit.count());
        }
        if (plan instanceof LogicalOperator.Scan scan) {
            return new TableScanOperator(store, scan.tableName());
        }
        if (plan instanceof LogicalOperator.IndexScan scan) {
            return TableScanOperator.indexScan(store, scan.tableName(), scan.column(), scan.range());
        }
        if (plan instanceof LogicalOperator.Filter filter) {
            return new FilterOperator(compile(filter.child(), choice), filter.predicate(), new ExpressionEvaluator());
        }
        if (plan instanceof LogicalOperator.Project project) {
            return new ProjectOperator(compile(project.child(), choice), project.columnIndexes());
        }
        throw new IllegalArgumentException("Unsupported logical plan: " + plan);
    }
}
