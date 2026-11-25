package minidb.exec;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import minidb.plan.LogicalOperator.AggregateCall;
import minidb.storage.Row;
import minidb.types.DataType;
import minidb.types.Value;

/**
 * Blocking hash aggregation. The first next() drains the child, keeping one accumulator per
 * aggregate per group (not the input rows), so peak memory grows with the number of groups.
 * Groups are keyed by typed values: NULL keys form one group and differ from the empty string.
 * Output rows contain the group key values, then one value per aggregate. A global aggregate
 * (no group keys) emits exactly one row, even for empty input; a grouped one emits no rows then.
 * Group output order is first-seen input order, which callers must not rely on without ORDER BY.
 */
public final class AggregateOperator extends UnaryOperator {
    /** AVG results carry this many fractional digits, rounded HALF_EVEN. */
    public static final int AVG_SCALE = 6;

    private final List<Integer> groupIndexes;
    private final List<AggregateCall> aggregates;
    private final Map<List<Value>, Accumulator[]> groups = new LinkedHashMap<>();
    private Iterator<Map.Entry<List<Value>, Accumulator[]>> output;

    public AggregateOperator(Operator child, List<Integer> groupIndexes, List<AggregateCall> aggregates) {
        super(child);
        this.groupIndexes = List.copyOf(groupIndexes);
        this.aggregates = List.copyOf(aggregates);
        if (this.groupIndexes.isEmpty() && this.aggregates.isEmpty()) {
            throw new IllegalArgumentException("Aggregate requires a group key or an aggregate");
        }
    }

    @Override protected Row readNext() {
        if (output == null) {
            if (groupIndexes.isEmpty()) groups.put(List.of(), newAccumulators());
            Row row;
            while ((row = child.next()) != null) {
                List<Value> key = new ArrayList<>(groupIndexes.size());
                for (int index : groupIndexes) key.add(row.values().get(index));
                Accumulator[] state = groups.computeIfAbsent(List.copyOf(key), k -> newAccumulators());
                for (Accumulator accumulator : state) accumulator.add(row);
            }
            output = groups.entrySet().iterator();
        }
        if (!output.hasNext()) return null;
        Map.Entry<List<Value>, Accumulator[]> group = output.next();
        List<Value> values = new ArrayList<>(group.getKey().size() + aggregates.size());
        values.addAll(group.getKey());
        for (Accumulator accumulator : group.getValue()) values.add(accumulator.result());
        return new Row(List.copyOf(values));
    }

    @Override protected void release() {
        groups.clear();
        output = null;
    }

    private Accumulator[] newAccumulators() {
        Accumulator[] state = new Accumulator[aggregates.size()];
        for (int i = 0; i < state.length; i++) state[i] = Accumulator.of(aggregates.get(i));
        return state;
    }

    /** Running state of one aggregate for one group. */
    abstract static class Accumulator {
        final AggregateCall call;

        Accumulator(AggregateCall call) {
            this.call = call;
        }

        static Accumulator of(AggregateCall call) {
            return switch (call.function()) {
                case COUNT -> new Count(call);
                case SUM, AVG -> new Sum(call);
                case MIN, MAX -> new Extreme(call);
            };
        }

        abstract void add(Row row);

        abstract Value result();

        /** The argument value, or null for COUNT(*). */
        Value argument(Row row) {
            return call.argumentIndex() < 0 ? null : row.values().get(call.argumentIndex());
        }
    }

    /** COUNT(*) counts every row; COUNT(column) counts non-NULL values. Never NULL. */
    static final class Count extends Accumulator {
        private long count;

        Count(AggregateCall call) {
            super(call);
        }

        @Override void add(Row row) {
            Value value = argument(row);
            if (value != null && value.isNull()) return;
            if (count == Long.MAX_VALUE) {
                throw new QueryExecutionException(call.label() + " overflows BIGINT");
            }
            count++;
        }

        @Override Value result() {
            return new Value(DataType.BIGINT, count);
        }
    }

    /** SUM and AVG over INTEGER input, ignoring NULLs; typed NULL when no value was seen. */
    static final class Sum extends Accumulator {
        private final ExactSum sum = new ExactSum();

        Sum(AggregateCall call) {
            super(call);
        }

        @Override void add(Row row) {
            Value value = argument(row);
            if (value.isNull()) return;
            sum.add(((Integer) value.raw()).longValue());
        }

        @Override Value result() {
            if (call.function() == minidb.sql.ast.Expression.AggregateFunction.AVG) {
                return sum.count() == 0 ? Value.nullValue(DataType.DECIMAL)
                        : new Value(DataType.DECIMAL, sum.average());
            }
            return sum.count() == 0 ? Value.nullValue(DataType.BIGINT)
                    : new Value(DataType.BIGINT, sum.toLongExact(call.label()));
        }
    }

    /** MIN and MAX keep the input type, ignore NULLs, and use the sort order of ORDER BY. */
    static final class Extreme extends Accumulator {
        private final boolean max;
        private Value best;

        Extreme(AggregateCall call) {
            super(call);
            max = call.function() == minidb.sql.ast.Expression.AggregateFunction.MAX;
        }

        @Override void add(Row row) {
            Value value = argument(row);
            if (value.isNull()) return;
            if (best == null) {
                best = value;
                return;
            }
            int order = value.type() == DataType.STRING
                    ? ((String) value.raw()).compareTo((String) best.raw())
                    : NumericValues.compare(value, best);
            if (max ? order > 0 : order < 0) best = value;
        }

        @Override Value result() {
            return best == null ? Value.nullValue(call.argumentType()) : best;
        }
    }

    /**
     * Exact integer sum with a count of added values. Uses a long until it would overflow, then
     * continues in BigInteger, so AVG stays exact and SUM overflow is detected only on output.
     */
    static final class ExactSum {
        private long small;
        private BigInteger big;
        private long count;

        void add(long value) {
            if (count == Long.MAX_VALUE) throw new QueryExecutionException("aggregate input count overflows BIGINT");
            count++;
            if (big != null) {
                big = big.add(BigInteger.valueOf(value));
                return;
            }
            long result = small + value;
            // Overflow iff both operands have the sign opposite to the result.
            if (((small ^ result) & (value ^ result)) < 0) {
                big = BigInteger.valueOf(small).add(BigInteger.valueOf(value));
            } else {
                small = result;
            }
        }

        long count() {
            return count;
        }

        BigInteger total() {
            return big != null ? big : BigInteger.valueOf(small);
        }

        /** The sum as a BIGINT, or a recoverable error naming {@code label} if it does not fit. */
        long toLongExact(String label) {
            if (big == null) return small;
            try {
                return big.longValueExact();
            } catch (ArithmeticException e) {
                throw new QueryExecutionException(label + " overflows BIGINT", e);
            }
        }

        /** Exact sum divided by count once, at {@link #AVG_SCALE} digits, HALF_EVEN. */
        BigDecimal average() {
            return average(total(), count);
        }

        static BigDecimal average(BigInteger total, long count) {
            return new BigDecimal(total).divide(BigDecimal.valueOf(count), AVG_SCALE, RoundingMode.HALF_EVEN);
        }
    }
}
