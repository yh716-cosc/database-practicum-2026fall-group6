package minidb.exec;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import minidb.plan.LogicalOperator.SortKey;
import minidb.storage.Row;
import minidb.types.DataType;
import minidb.types.Value;

class SortLimitOperatorTest {
    private static Row row(int key, String label) {
        return new Row(List.of(new Value(DataType.INTEGER, key), new Value(DataType.STRING, label)));
    }

    private static final class Child implements Operator {
        List<Row> rows;
        int reads, closes;
        RuntimeException openFailure, readFailure, closeFailure;
        Child(Row... rows) { this.rows = List.of(rows); }
        public void open() { if (openFailure != null) throw openFailure; }
        public Row next() {
            if (readFailure != null) throw readFailure;
            return reads < rows.size() ? rows.get(reads++) : eof();
        }
        private Row eof() { reads++; return null; }
        public void close() { closes++; if (closeFailure != null) throw closeFailure; }
    }

    @Test void limitDoesNotOverreadAndZeroDoesNotRead() {
        for (int count : List.of(0, 1)) {
            Child child = new Child(row(1, "a"), row(2, "b"));
            Operator limit = new LimitOperator(child, count);
            assertThrows(IllegalStateException.class, limit::next);
            limit.open();
            for (int i = 0; i < count; i++) assertNotNull(limit.next());
            assertNull(limit.next());
            assertNull(limit.next());
            assertEquals(count, child.reads);
            limit.close(); limit.close();
            assertEquals(1, child.closes);
            assertThrows(IllegalStateException.class, limit::next);
            assertThrows(IllegalStateException.class, limit::open);
        }
    }

    @Test void sortIsLazyStableAndComparesIntegersWithoutOverflow() {
        Row a = row(Integer.MAX_VALUE, "a"), b = row(Integer.MIN_VALUE, "b"), c = row(Integer.MAX_VALUE, "c");
        Child child = new Child(a, b, c);
        Operator sort = new SortOperator(child, List.of(new SortKey(0, true)));
        sort.open();
        assertEquals(0, child.reads);
        assertEquals(b, sort.next()); assertEquals(a, sort.next()); assertEquals(c, sort.next());
        assertNull(sort.next()); assertNull(sort.next());
        sort.close();
        assertEquals(1, child.closes);
        assertThrows(IllegalStateException.class, sort::next);
    }

    @Test void bothOperatorsCleanUpOpenReadAndCloseFailuresOnce() {
        for (boolean sorting : List.of(false, true)) {
            for (boolean duringOpen : List.of(false, true)) {
                Child child = new Child();
                RuntimeException failure = new IllegalStateException("primary");
                child.closeFailure = new IllegalStateException("cleanup");
                if (duringOpen) child.openFailure = failure; else child.readFailure = failure;
                Operator operator = sorting ? new SortOperator(child, List.of(new SortKey(0, true)))
                        : new LimitOperator(child, 1);
                if (!duringOpen) operator.open();
                assertSame(failure, assertThrows(IllegalStateException.class,
                        () -> { if (duringOpen) operator.open(); else operator.next(); }));
                assertArrayEquals(new Throwable[] {child.closeFailure}, failure.getSuppressed());
                operator.close();
                assertEquals(1, child.closes);
            }
        }
    }

    @Test void emptyInputAndExplicitEarlyClose() {
        for (boolean sorting : List.of(false, true)) {
            Child child = new Child();
            Operator operator = sorting ? new SortOperator(child, List.of(new SortKey(0, true)))
                    : new LimitOperator(child, 3);
            operator.open(); assertNull(operator.next()); assertNull(operator.next());
            assertEquals(1, child.closes);
            Child unread = new Child(row(1, "a"));
            Operator early = sorting ? new SortOperator(unread, List.of(new SortKey(0, true)))
                    : new LimitOperator(unread, 3);
            early.open(); early.close();
            assertEquals(0, unread.reads); assertEquals(1, unread.closes);
        }
    }

    @Test void sortComparisonFailureClosesChildAndSuppressesCleanupFailure() {
        Child child = new Child(row(2, "a"), row(1, "b"));
        child.closeFailure = new IllegalStateException("cleanup");
        Operator sort = new SortOperator(child, List.of(new SortKey(9, true)));
        sort.open();
        var failure = assertThrows(IndexOutOfBoundsException.class, sort::next);
        assertArrayEquals(new Throwable[] {child.closeFailure}, failure.getSuppressed());
        sort.close();
        assertEquals(1, child.closes);
    }
}
