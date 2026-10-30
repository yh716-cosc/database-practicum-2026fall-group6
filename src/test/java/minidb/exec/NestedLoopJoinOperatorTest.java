package minidb.exec;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import minidb.plan.BoundExpression;
import minidb.storage.Row;
import minidb.types.*;

class NestedLoopJoinOperatorTest {
    static Row row(int n) { return new Row(List.of(new Value(DataType.INTEGER, n))); }
    static class Probe implements Operator {
        final List<Row> rows;
        int opens, closes, reads, index;
        RuntimeException openFailure, readFailure, closeFailure;
        Probe(Row... rows) { this.rows = List.of(rows); }
        public void open() {
            assertEquals(0, opens++);
            if (openFailure != null) throw openFailure;
        }
        public Row next() {
            reads++;
            if (readFailure != null) throw readFailure;
            return index < rows.size() ? rows.get(index++) : null;
        }
        public void close() {
            closes++;
            if (closeFailure != null) throw closeFailure;
        }
    }
    NestedLoopJoinOperator join(Probe left, java.util.function.Supplier<Operator> factory) {
        return new NestedLoopJoinOperator(left, factory, new BoundExpression.Constant(true));
    }
    @Test void recreatesRightAndClosesAtEof() {
        Probe left = new Probe(row(1), row(2));
        List<Probe> rights = new ArrayList<>();
        var join = join(left, () -> { var p = new Probe(row(7), row(8)); rights.add(p); return p; });
        assertThrows(IllegalStateException.class, join::next);
        join.open();
        assertTrue(rights.isEmpty());
        assertThrows(IllegalStateException.class, join::open);
        List<Row> output = new ArrayList<>();
        Row r;
        while ((r = join.next()) != null) output.add(r);
        assertEquals(4, output.size());
        assertEquals(List.of(new Value(DataType.INTEGER, 2), new Value(DataType.INTEGER, 8)), output.get(3).values());
        assertNull(join.next());
        join.close(); join.close();
        assertEquals(1, left.closes);
        assertEquals(2, rights.size());
        rights.forEach(p -> { assertEquals(1, p.opens); assertEquals(1, p.closes); });
    }
    @Test void emptyLeftDoesNotOpenRight() {
        Probe left = new Probe();
        var join = join(left, () -> { throw new AssertionError("Should not scan right"); });
        join.open(); assertNull(join.next()); assertEquals(1, left.closes);
    }
    @Test void limitClosesActiveInputsWithoutExtraReads() {
        Probe left = new Probe(row(1), row(2)), right = new Probe(row(3), row(4));
        var limit = new LimitOperator(join(left, () -> right), 1);
        limit.open(); assertNotNull(limit.next()); assertNull(limit.next()); limit.close();
        assertEquals(1, left.reads); assertEquals(1, right.reads);
        assertEquals(1, left.closes); assertEquals(1, right.closes);
    }
    @Test void failuresCloseOwnedInputsAndPreservePrimaryException() {
        for (String stage : List.of("leftOpen", "leftRead", "factory", "rightOpen", "rightRead", "rightClose")) {
            Probe left = new Probe(row(1)), right = new Probe(row(2));
            RuntimeException failure = new RuntimeException(stage);
            switch (stage) {
                case "leftOpen" -> left.openFailure = failure;
                case "leftRead" -> left.readFailure = failure;
                case "rightOpen" -> right.openFailure = failure;
                case "rightRead" -> right.readFailure = failure;
                case "rightClose" -> right.closeFailure = failure;
            }
            var join = join(left, () -> { if (stage.equals("factory")) throw failure; return right; });
            if (stage.equals("leftOpen")) assertSame(failure, assertThrows(RuntimeException.class, join::open));
            else {
                join.open();
                if (stage.equals("rightClose")) assertNotNull(join.next());
                assertSame(failure, assertThrows(RuntimeException.class, join::next));
            }
            join.close();
            assertEquals(1, left.closes, stage);
            assertEquals(stage.startsWith("right") ? 1 : 0, right.closes, stage);
        }
    }
    @Test void closeAttemptsBothChildrenAndSuppressesSecondaryFailure() {
        Probe left = new Probe(row(1)), right = new Probe(row(2));
        var join = join(left, () -> right);
        join.open(); join.next();
        left.closeFailure = new RuntimeException("left close");
        right.readFailure = new RuntimeException("read");
        right.closeFailure = new RuntimeException("right close");
        var failure = assertThrows(RuntimeException.class, join::next);
        assertSame(right.readFailure, failure);
        assertSame(right.closeFailure, failure.getSuppressed()[0]);
        assertSame(left.closeFailure, failure.getSuppressed()[0].getSuppressed()[0]);
        join.close(); assertEquals(1, left.closes); assertEquals(1, right.closes);
    }

    @Test void outerJoinEmitsOneNullRowAndClosesBeforeReturningIt() {
        for (boolean swapped : List.of(false, true)) {
            Probe outer = new Probe(row(1), row(2));
            List<Probe> inners = new ArrayList<>();
            var join = new NestedLoopJoinOperator(outer, () -> {
                var p = new Probe(row(3)); inners.add(p); return p;
            }, new BoundExpression.Constant(false), true, swapped, List.of(DataType.INTEGER));
            join.open();
            Row result = join.next();
            assertEquals(swapped ? null : 1, result.values().get(0).raw());
            assertEquals(swapped ? 1 : null, result.values().get(1).raw());
            assertEquals(1, inners.get(0).closes);
            join.close(); join.close();
            assertEquals(1, outer.closes);
            assertEquals(1, inners.size());
            assertThrows(IllegalStateException.class, join::next);
        }
    }

    @Test void outerJoinFailureDoesNotEmitUnmatchedRow() {
        for (boolean swapped : List.of(false, true)) {
            Probe outer = new Probe(row(1)), inner = new Probe();
            inner.readFailure = new RuntimeException("scan failed");
            var join = new NestedLoopJoinOperator(outer, () -> inner,
                    new BoundExpression.Constant(false), true, swapped, List.of(DataType.INTEGER));
            join.open();
            assertSame(inner.readFailure, assertThrows(RuntimeException.class, join::next));
            assertEquals(1, outer.closes); assertEquals(1, inner.closes);
        }
    }
}
