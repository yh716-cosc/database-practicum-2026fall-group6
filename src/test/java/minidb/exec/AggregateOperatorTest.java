package minidb.exec;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import minidb.plan.LogicalOperator.AggregateCall;
import minidb.sql.ast.Expression.AggregateFunction;
import minidb.storage.Row;
import minidb.types.DataType;
import minidb.types.Value;

class AggregateOperatorTest {
    private static final AggregateCall COUNT_STAR = new AggregateCall(AggregateFunction.COUNT, -1, null, "COUNT(*)");

    private static AggregateCall call(AggregateFunction function, int index, DataType type) {
        return new AggregateCall(function, index, type, function + "(c" + index + ")");
    }

    private static Value str(String text) {
        return text == null ? Value.nullValue(DataType.STRING) : new Value(DataType.STRING, text);
    }

    private static Value num(Integer value) {
        return value == null ? Value.nullValue(DataType.INTEGER) : new Value(DataType.INTEGER, value);
    }

    private static Row row(String key, Integer value) {
        return new Row(List.of(str(key), num(value)));
    }

    private static final class Child implements Operator {
        final List<Row> rows;
        int reads, closes;
        RuntimeException readFailure;
        Child(Row... rows) { this.rows = List.of(rows); }
        public void open() {}
        public Row next() {
            if (readFailure != null && reads == 1) throw readFailure;
            return reads < rows.size() ? rows.get(reads++) : null;
        }
        public void close() { closes++; }
    }

    private static List<List<Object>> drain(Operator operator) {
        operator.open();
        List<List<Object>> out = new ArrayList<>();
        Row row;
        while ((row = operator.next()) != null) out.add(row.values().stream().map(Value::raw).toList());
        return out;
    }

    @Test void globalAggregatesOverEmptyInputEmitOneRow() {
        Child child = new Child();
        var op = new AggregateOperator(child, List.of(), List.of(COUNT_STAR,
                call(AggregateFunction.COUNT, 1, DataType.INTEGER), call(AggregateFunction.SUM, 1, DataType.INTEGER),
                call(AggregateFunction.AVG, 1, DataType.INTEGER), call(AggregateFunction.MIN, 0, DataType.STRING),
                call(AggregateFunction.MAX, 1, DataType.INTEGER)));
        op.open();
        Row result = op.next();
        assertEquals(Arrays.asList(0L, 0L, null, null, null, null), result.values().stream().map(Value::raw).toList());
        assertEquals(List.of(DataType.BIGINT, DataType.BIGINT, DataType.BIGINT, DataType.DECIMAL,
                DataType.STRING, DataType.INTEGER), result.values().stream().map(Value::type).toList());
        assertNull(op.next());
        assertNull(op.next());
        assertEquals(1, child.closes);
    }

    @Test void groupedAggregateOverEmptyInputEmitsNothing() {
        assertTrue(drain(new AggregateOperator(new Child(), List.of(0), List.of(COUNT_STAR))).isEmpty());
    }

    @Test void groupsTypedNullsTogetherAndApartFromEmptyString() {
        Child child = new Child(row("A", 1), row(null, 2), row("", 3), row(null, null), row("A", null));
        var result = drain(new AggregateOperator(child, List.of(0), List.of(COUNT_STAR,
                call(AggregateFunction.COUNT, 1, DataType.INTEGER), call(AggregateFunction.SUM, 1, DataType.INTEGER))));
        assertEquals(List.of(List.of("A", 2L, 1L, 1L), Arrays.asList(null, 2L, 1L, 2L), List.of("", 1L, 1L, 3L)),
                result);
    }

    @Test void minMaxUseNumericAndCaseSensitiveStringOrder() {
        Child child = new Child(row("b", -5), row("B", 7), row(null, null), row("a", Integer.MIN_VALUE));
        var result = drain(new AggregateOperator(child, List.of(), List.of(
                call(AggregateFunction.MIN, 0, DataType.STRING), call(AggregateFunction.MAX, 0, DataType.STRING),
                call(AggregateFunction.MIN, 1, DataType.INTEGER), call(AggregateFunction.MAX, 1, DataType.INTEGER))));
        assertEquals(List.of(List.of("B", "b", Integer.MIN_VALUE, 7)), result);
    }

    @Test void sumsPast32BitsAndAveragesWithScaleSix() {
        Child child = new Child(row("x", Integer.MAX_VALUE), row("x", Integer.MAX_VALUE), row("x", -1));
        var result = drain(new AggregateOperator(child, List.of(), List.of(
                call(AggregateFunction.SUM, 1, DataType.INTEGER), call(AggregateFunction.AVG, 1, DataType.INTEGER))));
        assertEquals(4294967293L, result.get(0).get(0));
        assertEquals(new BigDecimal("1431655764.333333"), result.get(0).get(1));
        var negative = drain(new AggregateOperator(new Child(row("x", -1), row("x", -1), row("x", 0)),
                List.of(), List.of(call(AggregateFunction.AVG, 1, DataType.INTEGER))));
        assertEquals(new BigDecimal("-0.666667"), negative.get(0).get(0));
    }

    @Test void averageRoundsHalfEvenAtTheSeventhDigit() {
        assertEquals(new BigDecimal("0.000000"),
                AggregateOperator.ExactSum.average(BigInteger.ONE, 2_000_000));
        assertEquals(new BigDecimal("0.000002"),
                AggregateOperator.ExactSum.average(BigInteger.valueOf(3), 2_000_000));
        assertEquals(new BigDecimal("-0.000002"),
                AggregateOperator.ExactSum.average(BigInteger.valueOf(-3), 2_000_000));
        assertEquals(new BigDecimal("7.600000"), AggregateOperator.ExactSum.average(BigInteger.valueOf(38), 5));
    }

    @Test void exactSumDetectsBigintOverflowOnlyWhenEmitting() {
        var sum = new AggregateOperator.ExactSum();
        sum.add(Long.MAX_VALUE);
        sum.add(1);
        QueryExecutionException ex = assertThrows(QueryExecutionException.class, () -> sum.toLongExact("SUM(x)"));
        assertTrue(ex.getMessage().contains("SUM(x) overflows BIGINT"));
        assertEquals(new BigDecimal("4611686018427387904.000000"), sum.average());
        sum.add(-2);
        assertEquals(Long.MAX_VALUE - 1, sum.toLongExact("SUM(x)"));
        var low = new AggregateOperator.ExactSum();
        low.add(Long.MIN_VALUE);
        low.add(-1);
        assertThrows(QueryExecutionException.class, () -> low.toLongExact("SUM(x)"));
    }

    @Test void failedChildReadClosesChildAndReleasesState() {
        Child child = new Child(row("A", 1), row("B", 2));
        child.readFailure = new IllegalStateException("disk");
        var op = new AggregateOperator(child, List.of(0), List.of(COUNT_STAR));
        op.open();
        assertSame(child.readFailure, assertThrows(IllegalStateException.class, op::next));
        assertEquals(1, child.closes);
        assertThrows(IllegalStateException.class, op::next);
    }

    @Test void earlyCloseAndLimitAboveAggregation() {
        Child child = new Child(row("A", 1), row("B", 2), row("C", 3));
        Operator limit = new LimitOperator(new AggregateOperator(child, List.of(0), List.of(COUNT_STAR)), 1);
        limit.open();
        assertNotNull(limit.next());
        assertNull(limit.next());
        assertEquals(3, child.reads);
        assertEquals(1, child.closes);
        Child unread = new Child(row("A", 1));
        var op = new AggregateOperator(unread, List.of(0), List.of(COUNT_STAR));
        op.open();
        op.close();
        op.close();
        assertEquals(0, unread.reads);
        assertEquals(1, unread.closes);
    }

    @Test void sortOrdersBigintAndDecimalWithNullsLast() {
        Child child = new Child(
                new Row(List.of(new Value(DataType.BIGINT, 5L), new Value(DataType.DECIMAL, new BigDecimal("1.5")))),
                new Row(List.of(Value.nullValue(DataType.BIGINT), new Value(DataType.DECIMAL, new BigDecimal("-2")))),
                new Row(List.of(new Value(DataType.BIGINT, -9L), Value.nullValue(DataType.DECIMAL))));
        var sorted = drain(new SortOperator(child, List.of(new minidb.plan.LogicalOperator.SortKey(0, true))));
        assertEquals(Arrays.asList(-9L, 5L, null), sorted.stream().map(r -> r.get(0)).toList());
    }
}
