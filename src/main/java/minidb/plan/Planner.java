package minidb.plan;

import minidb.catalog.Catalog;
import minidb.catalog.Table;
import minidb.sql.ast.*;
import minidb.types.DataType;
import minidb.types.Value;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Validates single-table or two-table queries without scanning rows. */
public final class Planner {
    /**
     * Resolves table/column names and checks types using catalog metadata, then
     * returns Project(Limit(Sort(Filter(input)))), omitting optional clauses.
     * Input is a scan or join; join rows contain left columns then right columns.
     * This method does not open a data cursor or evaluate any rows.
     */
    public LogicalOperator plan(Statement statement, Catalog catalog) {
        if (!(statement instanceof Query query)) {
            throw new PlanningException("Only SELECT queries are supported");
        }
        if (query.from() == null || query.from().size() != 1 || query.from().get(0) == null) {
            throw new PlanningException("Exactly one FROM table is required");
        }
        if (query.joins() == null || query.joins().size() > 1) {
            throw new PlanningException("Only two-table JOIN is supported");
        }
        TableRef from = query.from().get(0);
        if (from.name() == null || from.name().isBlank()) throw new PlanningException("Table name is required");
        if (query.select() == null || query.select().isEmpty()) throw new PlanningException("SELECT list cannot be empty");
        for (SelectItem item : query.select()) {
            if (item == null || item.alias() != null) throw new PlanningException("Invalid SELECT item or unsupported alias");
            if (!(item.expression() instanceof ColumnRef) && !(item.expression() instanceof Expression.Star)) {
                throw new PlanningException("SELECT supports only columns or *");
            }
            if (item.expression() instanceof Expression.Star && query.select().size() != 1) {
                throw new PlanningException("SELECT * cannot be mixed with other items");
            }
        }
        Table table = catalog.getTable(from.name());
        if (table == null) throw new PlanningException("Unknown table: " + from.name());
        Binding binding = new Binding();
        binding.add(from, table);
        LogicalOperator child = new LogicalOperator.Scan(from.name());
        if (!query.joins().isEmpty()) {
            Join join = query.joins().get(0);
            if (join == null || join.type() == null || join.right() == null || join.right().name() == null) {
                throw new PlanningException("Invalid JOIN table");
            }
            Table right = catalog.getTable(join.right().name());
            if (right == null) throw new PlanningException("Unknown table: " + join.right().name());
            int leftWidth = binding.width;
            binding.add(join.right(), right);
            BoundExpression.Predicate condition = binding.predicate(join.condition(), 0);
            if (!(condition instanceof BoundExpression.Comparison comparison)
                    || comparison.op() != Expression.ComparisonOp.EQ
                    || !(comparison.left() instanceof BoundExpression.Column a)
                    || !(comparison.right() instanceof BoundExpression.Column b)
                    || (a.index() < leftWidth) == (b.index() < leftWidth)) {
                throw new PlanningException("JOIN ON requires equality between columns from opposite tables");
            }
            child = new LogicalOperator.Join(child, new LogicalOperator.Scan(join.right().name()), condition,
                    join.type(), table.columns().stream().map(c -> c.type()).toList(),
                    right.columns().stream().map(c -> c.type()).toList());
        }
        if (query.where() != null) child = new LogicalOperator.Filter(child, binding.predicate(query.where(), 0));
        if (query.orderBy() == null) throw new PlanningException("ORDER BY list is required");
        List<LogicalOperator.SortKey> keys = new ArrayList<>();
        for (OrderBy key : query.orderBy()) {
            if (key == null || key.column() == null) throw new PlanningException("Invalid ORDER BY key");
            keys.add(new LogicalOperator.SortKey(binding.column(key.column()).index(), key.ascending()));
        }
        if (!keys.isEmpty()) child = new LogicalOperator.Sort(child, keys);
        if (query.limit() != null) {
            if (query.limit() < 0) throw new PlanningException("LIMIT must be non-negative");
            child = new LogicalOperator.Limit(child, query.limit());
        }
        List<Integer> indexes = new ArrayList<>();
        for (SelectItem item : query.select()) {
            if (item.expression() instanceof Expression.Star) {
                for (int i = 0; i < binding.width; i++) indexes.add(i);
            } else {
                indexes.add(binding.column((ColumnRef) item.expression()).index());
            }
        }
        return new LogicalOperator.Project(child, List.copyOf(indexes));
    }

    private static final class Binding {
        private final Map<String, Map<String, BoundExpression.Column>> tables = new HashMap<>();
        private int width;

        void add(TableRef ref, Table table) {
            String qualifier = ref.alias() == null ? ref.name() : ref.alias();
            if (qualifier == null || qualifier.isBlank() || tables.containsKey(qualifier)) {
                throw new PlanningException("Duplicate or invalid table qualifier: " + qualifier);
            }
            Map<String, BoundExpression.Column> columns = new HashMap<>();
            tables.put(qualifier, columns);
            if (table.columns() == null || table.columns().isEmpty()) throw new PlanningException("Table has no columns");
            for (int i = 0; i < table.columns().size(); i++) {
                var column = table.columns().get(i);
                if (column == null || column.name() == null || column.type() == null) {
                    throw new PlanningException("Invalid column definition");
                }
                if (columns.putIfAbsent(column.name(), new BoundExpression.Column(width + i, column.type())) != null) {
                    throw new PlanningException("Ambiguous column: " + column.name());
                }
            }
            width += table.columns().size();
        }

        BoundExpression.Column column(ColumnRef ref) {
            if (ref.table() != null) {
                var columns = tables.get(ref.table());
                if (columns == null) throw new PlanningException("Unknown table qualifier: " + ref.table());
                var result = columns.get(ref.name());
                if (result == null) throw new PlanningException("Unknown column: " + ref.name());
                return result;
            }
            BoundExpression.Column result = null;
            for (var columns : tables.values()) {
                var candidate = columns.get(ref.name());
                if (candidate != null) {
                    if (result != null) throw new PlanningException("Ambiguous column: " + ref.name());
                    result = candidate;
                }
            }
            if (result == null) throw new PlanningException("Unknown column: " + ref.name());
            return result;
        }

        BoundExpression.Scalar scalar(Expression expression) {
            if (expression instanceof ColumnRef ref) return column(ref);
            if (expression instanceof Expression.IntLiteral literal) {
                return new BoundExpression.Literal(new Value(DataType.INTEGER, literal.value()));
            }
            if (expression instanceof Expression.StringLiteral literal && literal.value() != null) {
                return new BoundExpression.Literal(new Value(DataType.STRING, literal.value()));
            }
            throw new PlanningException("Expected a column, integer, or string operand");
        }

        BoundExpression.Predicate predicate(Expression expression, int depth) {
            // Flat AND/OR chains also create deep trees; fail before recursive binding overflows.
            if (depth > 256) throw new PlanningException("Predicate tree exceeds depth limit of 256");
            if (expression instanceof Expression.IsNull check) {
                return new BoundExpression.IsNull(scalar(check.operand()), check.negated());
            }
            if (expression instanceof Expression.And and) {
                return new BoundExpression.And(predicate(and.left(), depth + 1), predicate(and.right(), depth + 1));
            }
            if (expression instanceof Expression.Or or) {
                return new BoundExpression.Or(predicate(or.left(), depth + 1), predicate(or.right(), depth + 1));
            }
            if (expression instanceof Expression.Not not) return new BoundExpression.Not(predicate(not.child(), depth + 1));
            if (expression instanceof Expression.Comparison comparison) {
                BoundExpression.Scalar left = scalar(comparison.left());
                BoundExpression.Scalar right = scalar(comparison.right());
                if (comparison.op() == null) throw new PlanningException("Comparison operator is required");
                if (left.type() != right.type()) throw new PlanningException("Comparison requires matching types");
                if (left.type() == DataType.STRING && comparison.op() != Expression.ComparisonOp.EQ
                        && comparison.op() != Expression.ComparisonOp.NE) {
                    throw new PlanningException("STRING comparison only supports = and <>");
                }
                return new BoundExpression.Comparison(left, comparison.op(), right);
            }
            throw new PlanningException("WHERE and boolean operands must be predicates");
        }
    }
}
