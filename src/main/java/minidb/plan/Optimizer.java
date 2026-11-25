package minidb.plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import minidb.catalog.Catalog;
import minidb.exec.ExpressionEvaluator;
import minidb.index.KeyRange;
import minidb.plan.BoundExpression.*;
import minidb.sql.ast.Expression.ComparisonOp;
import minidb.types.DataType;

/**
 * Rule-based rewrites of fully bound, type-checked plans. No data reads; with a catalog,
 * index declarations (metadata) are consulted to choose index scans.
 */
public final class Optimizer {
    private final Catalog catalog;

    /** Without a catalog no indexes are known, so every table is scanned. */
    public Optimizer() {
        this(null);
    }

    public Optimizer(Catalog catalog) {
        this.catalog = catalog;
    }

    public LogicalOperator optimize(LogicalOperator plan) {
        if (plan instanceof LogicalOperator.Join join) {
            return new LogicalOperator.Join(optimize(join.left()), optimize(join.right()), join.condition(),
                    join.type(), join.leftTypes(), join.rightTypes());
        }
        if (plan instanceof LogicalOperator.Limit limit) {
            LogicalOperator child = optimize(limit.child());
            if (child instanceof LogicalOperator.Limit inner) {
                return new LogicalOperator.Limit(inner.child(), Math.min(limit.count(), inner.count()));
            }
            return new LogicalOperator.Limit(child, limit.count());
        }
        if (plan instanceof LogicalOperator.Sort sort) {
            LogicalOperator child = optimize(sort.child());
            if (child instanceof LogicalOperator.Sort inner && inner.keys().equals(sort.keys())) {
                child = inner.child();
            }
            return new LogicalOperator.Sort(child, sort.keys());
        }
        if (plan instanceof LogicalOperator.Aggregate aggregate) {
            // Grouping is a boundary: rewrite below it, but never move HAVING filters,
            // sorts, or projections across it (their indexes refer to the aggregate output).
            return new LogicalOperator.Aggregate(optimize(aggregate.child()), aggregate.groupIndexes(),
                    aggregate.aggregates());
        }
        if (plan instanceof LogicalOperator.Scan || plan instanceof LogicalOperator.IndexScan) {
            return plan;
        }
        if (plan instanceof LogicalOperator.Filter filter) {
            LogicalOperator child = optimize(filter.child());
            Predicate predicate = simplify(filter.predicate());
            if (predicate instanceof Constant constant && constant.value()) {
                return child;
            }
            if (child instanceof LogicalOperator.Scan scan && catalog != null) {
                child = accessPath(scan, predicate);
            }
            // Even a false predicate must scan: malformed CSV remains an error.
            // Index builds validate every record, so index scans preserve this.
            return new LogicalOperator.Filter(child, predicate);
        }
        if (plan instanceof LogicalOperator.Project project) {
            LogicalOperator child = optimize(project.child());
            List<Integer> indexes = List.copyOf(project.columnIndexes());
            if (child instanceof LogicalOperator.Project inner) {
                indexes = indexes.stream().map(inner.columnIndexes()::get).toList();
                child = inner.child();
            }
            return new LogicalOperator.Project(child, indexes);
        }
        throw new IllegalArgumentException("Unsupported logical plan: " + plan);
    }

    /**
     * Picks an index range from top-level AND conjuncts of the form "column op literal" on
     * an indexed column, preferring a column compared with =. The caller keeps the full
     * predicate above the index scan, so rows outside the conjuncts are still filtered.
     */
    private LogicalOperator accessPath(LogicalOperator.Scan scan, Predicate predicate) {
        List<String> indexed = catalog.indexedColumns(scan.tableName());
        if (indexed.isEmpty()) {
            return scan;
        }
        List<String> names = catalog.getTable(scan.tableName()).columns().stream()
                .map(column -> column.name()).toList();
        Map<Integer, KeyRange> ranges = new LinkedHashMap<>();
        Set<Integer> equalities = new LinkedHashSet<>();
        for (Predicate conjunct : conjuncts(predicate, new ArrayList<>())) {
            if (!(conjunct instanceof Comparison comparison)) continue;
            Column column;
            Literal literal;
            ComparisonOp op = comparison.op();
            if (comparison.left() instanceof Column left && comparison.right() instanceof Literal right) {
                column = left;
                literal = right;
            } else if (comparison.left() instanceof Literal left && comparison.right() instanceof Column right) {
                column = right;
                literal = left;
                op = flip(op);
            } else {
                continue;
            }
            if (column.type() != DataType.INTEGER || literal.value().isNull()
                    || !indexed.contains(names.get(column.index()))) continue;
            long value = (Integer) literal.value().raw();
            KeyRange bound = switch (op) {
                case EQ -> new KeyRange(value, value);
                case LT -> new KeyRange(Integer.MIN_VALUE, value - 1);
                case LE -> new KeyRange(Integer.MIN_VALUE, value);
                case GT -> new KeyRange(value + 1, Integer.MAX_VALUE);
                case GE -> new KeyRange(value, Integer.MAX_VALUE);
                case NE -> null;
            };
            if (bound == null) continue;
            ranges.merge(column.index(), bound, KeyRange::intersect);
            if (op == ComparisonOp.EQ) equalities.add(column.index());
        }
        if (ranges.isEmpty()) {
            return scan;
        }
        int chosen = (equalities.isEmpty() ? ranges.keySet() : equalities).iterator().next();
        return new LogicalOperator.IndexScan(scan.tableName(), names.get(chosen), ranges.get(chosen));
    }

    private static List<Predicate> conjuncts(Predicate predicate, List<Predicate> out) {
        if (predicate instanceof And and) {
            conjuncts(and.left(), out);
            conjuncts(and.right(), out);
        } else {
            out.add(predicate);
        }
        return out;
    }

    /** The operator that keeps the comparison true when its operands are swapped. */
    private static ComparisonOp flip(ComparisonOp op) {
        return switch (op) {
            case LT -> ComparisonOp.GT;
            case LE -> ComparisonOp.GE;
            case GT -> ComparisonOp.LT;
            case GE -> ComparisonOp.LE;
            case EQ, NE -> op;
        };
    }

    private Predicate simplify(Predicate predicate) {
        if (predicate instanceof IsNull) return predicate;
        if (predicate instanceof Constant) {
            return predicate;
        }
        if (predicate instanceof Comparison comparison) {
            if (comparison.left() instanceof Literal left && comparison.right() instanceof Literal right
                    && !left.value().isNull() && !right.value().isNull()) {
                return new Constant(new ExpressionEvaluator().test(comparison, null));
            }
            return predicate;
        }
        if (predicate instanceof Not not) {
            Predicate child = simplify(not.child());
            if (child instanceof Constant constant) {
                return new Constant(!constant.value());
            }
            if (child instanceof Not nested) {
                return nested.child();
            }
            return new Not(child);
        }
        if (predicate instanceof And and) {
            Predicate left = simplify(and.left());
            Predicate right = simplify(and.right());
            if (left instanceof Constant constant) {
                return constant.value() ? right : left;
            }
            if (right instanceof Constant constant) {
                return constant.value() ? left : right;
            }
            return new And(left, right);
        }
        if (predicate instanceof Or or) {
            Predicate left = simplify(or.left());
            Predicate right = simplify(or.right());
            if (left instanceof Constant constant) {
                return constant.value() ? left : right;
            }
            if (right instanceof Constant constant) {
                return constant.value() ? right : left;
            }
            return new Or(left, right);
        }
        throw new IllegalArgumentException("Unsupported predicate: " + predicate);
    }
}
