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
import java.util.function.Function;

/** Validates single-table or two-table queries without scanning rows. */
public final class Planner {
    private static final int MAX_PREDICATE_DEPTH = 256;

    /**
     * Resolves table/column names and checks types using catalog metadata, then
     * returns Project(Limit(Sort(Filter(input)))), omitting optional clauses.
     * Input is a scan or join; join rows contain left columns then right columns.
     *
     * <p>A query with GROUP BY, or with an aggregate call in SELECT, HAVING, or ORDER BY, is an
     * aggregate query and instead returns
     * Project(Limit(Sort(Filter[HAVING](Aggregate(Filter[WHERE](input)))))). HAVING, ORDER BY,
     * and SELECT then bind against the Aggregate output layout (group keys, then aggregates,
     * including aggregates used only by HAVING or ORDER BY, which the projection hides).
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
            if (!(item.expression() instanceof ColumnRef) && !(item.expression() instanceof Expression.Star)
                    && !(item.expression() instanceof Expression.Aggregate)) {
                throw new PlanningException("SELECT supports only columns, aggregate functions, or *");
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
            BoundExpression.Predicate condition = binding.predicate(join.condition(),
                    binding.inputScalars("JOIN ON"), 0);
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
        if (query.where() != null) {
            child = new LogicalOperator.Filter(child,
                    binding.predicate(query.where(), binding.inputScalars("WHERE"), 0));
        }
        if (query.orderBy() == null) throw new PlanningException("ORDER BY list is required");
        if (isAggregateQuery(query)) return planAggregate(query, binding, child);
        if (query.having() != null) {
            throw new PlanningException("HAVING requires GROUP BY or an aggregate function");
        }
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

    private static boolean isAggregateQuery(Query query) {
        if (query.groupBy() != null && !query.groupBy().isEmpty()) return true;
        for (SelectItem item : query.select()) {
            if (containsAggregate(item.expression(), 0)) return true;
        }
        if (containsAggregate(query.having(), 0)) return true;
        for (OrderBy key : query.orderBy()) {
            if (key != null && containsAggregate(key.key(), 0)) return true;
        }
        return false;
    }

    private static boolean containsAggregate(Expression expression, int depth) {
        if (depth > MAX_PREDICATE_DEPTH) {
            throw new PlanningException("Predicate tree exceeds depth limit of " + MAX_PREDICATE_DEPTH);
        }
        if (expression instanceof Expression.Aggregate) return true;
        if (expression instanceof Expression.And and) {
            return containsAggregate(and.left(), depth + 1) || containsAggregate(and.right(), depth + 1);
        }
        if (expression instanceof Expression.Or or) {
            return containsAggregate(or.left(), depth + 1) || containsAggregate(or.right(), depth + 1);
        }
        if (expression instanceof Expression.Not not) return containsAggregate(not.child(), depth + 1);
        if (expression instanceof Expression.IsNull check) return containsAggregate(check.operand(), depth + 1);
        if (expression instanceof Expression.Comparison comparison) {
            return containsAggregate(comparison.left(), depth + 1)
                    || containsAggregate(comparison.right(), depth + 1);
        }
        return false;
    }

    /** Builds the aggregate tail above the scan/join and WHERE filter already in {@code input}. */
    private static LogicalOperator planAggregate(Query query, Binding binding, LogicalOperator input) {
        AggregateScope scope = new AggregateScope(binding);
        if (query.groupBy() != null) {
            for (ColumnRef key : query.groupBy()) {
                if (key == null) throw new PlanningException("Invalid GROUP BY column");
                scope.addGroupKey(binding.column(key));
            }
        }
        // Bind in clause order so aggregate interning and output layout are deterministic.
        List<Integer> projection = new ArrayList<>();
        for (SelectItem item : query.select()) {
            if (item.expression() instanceof Expression.Star) {
                throw new PlanningException("SELECT * is not allowed with GROUP BY or aggregate functions");
            }
            projection.add(scope.outputColumn(item.expression(), "SELECT").index());
        }
        BoundExpression.Predicate having = query.having() == null ? null
                : binding.predicate(query.having(), e -> scope.scalar(e, "HAVING"), 0);
        List<LogicalOperator.SortKey> keys = new ArrayList<>();
        for (OrderBy key : query.orderBy()) {
            if (key == null || key.key() == null) throw new PlanningException("Invalid ORDER BY key");
            keys.add(new LogicalOperator.SortKey(scope.outputColumn(key.key(), "ORDER BY").index(),
                    key.ascending()));
        }
        LogicalOperator child = new LogicalOperator.Aggregate(input, scope.groupIndexes, scope.calls);
        if (having != null) child = new LogicalOperator.Filter(child, having);
        if (!keys.isEmpty()) child = new LogicalOperator.Sort(child, keys);
        if (query.limit() != null) {
            if (query.limit() < 0) throw new PlanningException("LIMIT must be non-negative");
            child = new LogicalOperator.Limit(child, query.limit());
        }
        return new LogicalOperator.Project(child, List.copyOf(projection));
    }

    /**
     * Name resolution above an Aggregate. Group keys come first in the output layout, then
     * aggregates; equivalent calls (same function and input column) share one slot.
     */
    private static final class AggregateScope {
        private record CallKey(Expression.AggregateFunction function, int argumentIndex) {}

        private final Binding input;
        private final List<Integer> groupIndexes = new ArrayList<>();
        private final List<DataType> groupTypes = new ArrayList<>();
        private final List<LogicalOperator.AggregateCall> calls = new ArrayList<>();
        private final Map<CallKey, Integer> interned = new HashMap<>();

        AggregateScope(Binding input) {
            this.input = input;
        }

        /** Repeated GROUP BY keys are accepted and grouped once. */
        void addGroupKey(BoundExpression.Column column) {
            if (!calls.isEmpty()) throw new IllegalStateException("Group keys must precede aggregates");
            if (!groupIndexes.contains(column.index())) {
                groupIndexes.add(column.index());
                groupTypes.add(column.type());
            }
        }

        /** Binds a grouped column, aggregate call, or literal to the aggregate output layout. */
        BoundExpression.Scalar scalar(Expression expression, String clause) {
            if (expression instanceof Expression.IntLiteral || expression instanceof Expression.StringLiteral) {
                return input.literal(expression);
            }
            return outputColumn(expression, clause);
        }

        BoundExpression.Column outputColumn(Expression expression, String clause) {
            if (expression instanceof ColumnRef ref) {
                BoundExpression.Column column = input.column(ref);
                int position = groupIndexes.indexOf(column.index());
                if (position < 0) {
                    throw new PlanningException("Column " + text(ref) + " in " + clause
                            + " must appear in GROUP BY or be used in an aggregate function");
                }
                return new BoundExpression.Column(position, column.type());
            }
            if (expression instanceof Expression.Aggregate aggregate) {
                LogicalOperator.AggregateCall call = bind(aggregate);
                Integer slot = interned.get(new CallKey(call.function(), call.argumentIndex()));
                if (slot == null) {
                    slot = calls.size();
                    calls.add(call);
                    interned.put(new CallKey(call.function(), call.argumentIndex()), slot);
                }
                return new BoundExpression.Column(groupIndexes.size() + slot, call.resultType());
            }
            if (expression instanceof Expression.IntLiteral || expression instanceof Expression.StringLiteral) {
                throw new PlanningException(clause + " items must be columns or aggregate functions");
            }
            throw new PlanningException("Expected a column, aggregate function, integer, or string operand in "
                    + clause);
        }

        private LogicalOperator.AggregateCall bind(Expression.Aggregate aggregate) {
            Expression.AggregateFunction function = aggregate.function();
            if (function == null) throw new PlanningException("Aggregate function is required");
            if (aggregate.argument() instanceof Expression.Star) {
                if (function != Expression.AggregateFunction.COUNT) {
                    throw new PlanningException(function + "(*) is not supported; only COUNT accepts *");
                }
                return new LogicalOperator.AggregateCall(function, -1, null, "COUNT(*)");
            }
            if (!(aggregate.argument() instanceof ColumnRef ref)) {
                throw new PlanningException(function + " argument must be a column");
            }
            BoundExpression.Column column = input.column(ref);
            if ((function == Expression.AggregateFunction.SUM || function == Expression.AggregateFunction.AVG)
                    && column.type() != DataType.INTEGER) {
                throw new PlanningException(function + " requires an INTEGER column, but " + text(ref)
                        + " is " + column.type());
            }
            return new LogicalOperator.AggregateCall(function, column.index(), column.type(),
                    function + "(" + text(ref) + ")");
        }

        private static String text(ColumnRef ref) {
            return ref.table() == null ? ref.name() : ref.table() + "." + ref.name();
        }
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

        /** Scalars over the scan/join row; aggregate calls are rejected in this clause. */
        Function<Expression, BoundExpression.Scalar> inputScalars(String clause) {
            return expression -> {
                if (expression instanceof Expression.Aggregate) {
                    throw new PlanningException("Aggregate functions are not allowed in " + clause);
                }
                if (expression instanceof ColumnRef ref) return column(ref);
                return literal(expression);
            };
        }

        BoundExpression.Scalar literal(Expression expression) {
            if (expression instanceof Expression.IntLiteral literal) {
                return new BoundExpression.Literal(new Value(DataType.INTEGER, literal.value()));
            }
            if (expression instanceof Expression.StringLiteral literal && literal.value() != null) {
                return new BoundExpression.Literal(new Value(DataType.STRING, literal.value()));
            }
            throw new PlanningException("Expected a column, integer, or string operand");
        }

        /**
         * Binds a WHERE, ON, or HAVING predicate; {@code scalars} resolves its operands. Numeric
         * types (INTEGER, BIGINT, DECIMAL) compare with each other exactly; otherwise types must match.
         */
        BoundExpression.Predicate predicate(Expression expression,
                                            Function<Expression, ? extends BoundExpression.Scalar> scalars,
                                            int depth) {
            // Flat AND/OR chains also create deep trees; fail before recursive binding overflows.
            if (depth > MAX_PREDICATE_DEPTH) {
                throw new PlanningException("Predicate tree exceeds depth limit of " + MAX_PREDICATE_DEPTH);
            }
            if (expression instanceof Expression.IsNull check) {
                return new BoundExpression.IsNull(scalars.apply(check.operand()), check.negated());
            }
            if (expression instanceof Expression.And and) {
                return new BoundExpression.And(predicate(and.left(), scalars, depth + 1),
                        predicate(and.right(), scalars, depth + 1));
            }
            if (expression instanceof Expression.Or or) {
                return new BoundExpression.Or(predicate(or.left(), scalars, depth + 1),
                        predicate(or.right(), scalars, depth + 1));
            }
            if (expression instanceof Expression.Not not) {
                return new BoundExpression.Not(predicate(not.child(), scalars, depth + 1));
            }
            if (expression instanceof Expression.Comparison comparison) {
                BoundExpression.Scalar left = scalars.apply(comparison.left());
                BoundExpression.Scalar right = scalars.apply(comparison.right());
                if (comparison.op() == null) throw new PlanningException("Comparison operator is required");
                if (left.type() != right.type() && !(left.type().isNumeric() && right.type().isNumeric())) {
                    throw new PlanningException("Comparison requires matching types");
                }
                if (left.type() == DataType.STRING && comparison.op() != Expression.ComparisonOp.EQ
                        && comparison.op() != Expression.ComparisonOp.NE) {
                    throw new PlanningException("STRING comparison only supports = and <>");
                }
                return new BoundExpression.Comparison(left, comparison.op(), right);
            }
            throw new PlanningException("WHERE, HAVING, and boolean operands must be predicates");
        }
    }
}
