package minidb.plan;

import java.util.Optional;
import minidb.catalog.Catalog;
import minidb.sql.ast.Expression.ComparisonOp;
import minidb.sql.ast.Join.Type;
import minidb.storage.StorageException;
import minidb.storage.TableStatistics;
import minidb.storage.TableStore;

/** Estimates and chooses a physical nested-loop direction for a two-table inner join. */
public final class CostBasedOptimizer {
    private final Catalog catalog;
    private final TableStore store;

    public CostBasedOptimizer(Catalog catalog, TableStore store) {
        this.catalog = catalog;
        this.store = store;
    }

    public record JoinChoice(LogicalOperator.Join join, double estimatedRows,
                             double leftOuterScanBytes, double rightOuterScanBytes) {
        public boolean driveFromRight() {
            return rightOuterScanBytes < leftOuterScanBytes;
        }
    }

    public Optional<JoinChoice> chooseJoin(LogicalOperator plan) {
        // Keep the existing row prefix for LIMIT, including ties under ORDER BY.
        while (true) {
            if (plan instanceof LogicalOperator.Project project) {
                plan = project.child();
            } else if (plan instanceof LogicalOperator.Filter filter) {
                plan = filter.child();
            } else if (plan instanceof LogicalOperator.Sort sort) {
                plan = sort.child();
            } else {
                break;
            }
        }

        if (!(plan instanceof LogicalOperator.Join join) || join.type() != Type.INNER
                || !(join.left() instanceof LogicalOperator.Scan left)
                || !(join.right() instanceof LogicalOperator.Scan right)
                || !(join.condition() instanceof BoundExpression.Comparison comparison)
                || comparison.op() != ComparisonOp.EQ
                || !(comparison.left() instanceof BoundExpression.Column first)
                || !(comparison.right() instanceof BoundExpression.Column second)) {
            return Optional.empty();
        }

        var leftColumns = catalog.getTable(left.tableName()).columns();
        var rightColumns = catalog.getTable(right.tableName()).columns();
        int leftWidth = leftColumns.size();
        int leftIndex = Math.min(first.index(), second.index());
        int rightIndex = Math.max(first.index(), second.index()) - leftWidth;
        if (leftIndex < 0 || leftIndex >= leftWidth
                || rightIndex < 0 || rightIndex >= rightColumns.size()) {
            return Optional.empty();
        }

        TableStatistics leftStats;
        TableStatistics rightStats;
        try {
            leftStats = store.statistics(left.tableName());
            rightStats = left.tableName().equals(right.tableName())
                    ? leftStats : store.statistics(right.tableName());
        } catch (StorageException | UnsupportedOperationException e) {
            // Let the original execution path decide which data it needs to read.
            return Optional.empty();
        }

        var leftKey = leftStats.columns().get(leftColumns.get(leftIndex).name());
        var rightKey = rightStats.columns().get(rightColumns.get(rightIndex).name());
        if (leftKey == null || rightKey == null) {
            return Optional.empty();
        }

        long distinct = Math.max(leftKey.distinctCount(), rightKey.distinctCount());
        double estimatedRows = distinct == 0 ? 0
                : (double) (leftStats.rowCount() - leftKey.nullCount())
                        * (rightStats.rowCount() - rightKey.nullCount()) / distinct;
        return Optional.of(new JoinChoice(join, estimatedRows,
                scanBytes(leftStats, rightStats), scanBytes(rightStats, leftStats)));
    }

    private static double scanBytes(TableStatistics outer, TableStatistics inner) {
        return outer.dataBytes() + (double) outer.rowCount() * inner.dataBytes();
    }
}

