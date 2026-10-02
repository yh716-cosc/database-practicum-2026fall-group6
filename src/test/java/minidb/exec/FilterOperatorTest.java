package minidb.exec;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import minidb.catalog.Table;
import minidb.plan.BoundExpression.*;
import minidb.sql.ast.Expression.ComparisonOp;
import minidb.storage.DirectoryTableStore;
import minidb.storage.Row;
import minidb.types.DataType;
import minidb.types.Value;

class FilterOperatorTest {
    @TempDir Path directory;

    private static Row row(int age, String name) {
        return new Row(List.of(new Value(DataType.INTEGER, age), new Value(DataType.STRING, name)));
    }

    private static Predicate adult() {
        return new Comparison(new Column(0, DataType.INTEGER), ComparisonOp.GE,
                new Literal(new Value(DataType.INTEGER, 18)));
    }

    private static FilterOperator filter(Operator child, Predicate predicate) {
        return new FilterOperator(child, predicate, new ExpressionEvaluator());
    }

    private static final class Child implements Operator {
        final List<Row> rows;
        int opens, reads, closes;
        RuntimeException openFailure, readFailure, closeFailure;

        Child(Row... rows) { this.rows = List.of(rows); }

        public void open() {
            opens++;
            if (openFailure != null) { throw openFailure; }
        }

        public Row next() {
            int index = reads++;
            if (readFailure != null) { throw readFailure; }
            return index < rows.size() ? rows.get(index) : null;
        }

        public void close() {
            closes++;
            if (closeFailure != null) { throw closeFailure; }
        }
    }

    @Test
    void skipsNonmatchingRowsAndStopsReadingAtEachMatch() {
        Row first = row(18, "Alice");
        Row last = row(30, "Bob");
        Child child = new Child(row(17, "A"), first, row(10, "B"), first, last);
        FilterOperator filter = filter(child, adult());
        filter.open();
        assertEquals(0, child.reads);
        assertSame(first, filter.next());
        assertEquals(2, child.reads);
        assertSame(first, filter.next());
        assertEquals(4, child.reads);
        assertSame(last, filter.next());
        assertEquals(5, child.reads);
        assertNull(filter.next());
        assertEquals(1, child.closes);
        assertNull(filter.next());
        assertEquals(6, child.reads);
        assertThrows(IllegalStateException.class, filter::open);
        filter.close();
        filter.close();
        assertEquals(1, child.closes);
        assertThrows(IllegalStateException.class, filter::next);
    }

    @Test
    void returnsEveryRowWhenAllMatch() {
        Child child = new Child(row(18, "A"), row(19, "B"));
        FilterOperator filter = filter(child, adult());
        filter.open();
        assertSame(child.rows.get(0), filter.next());
        assertSame(child.rows.get(1), filter.next());
        assertNull(filter.next());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void emptyOrEntirelyRejectedInputReachesEof(boolean empty) {
        Child child = empty ? new Child() : new Child(row(10, "A"), row(17, "B"));
        FilterOperator filter = filter(child, adult());
        filter.open();
        assertNull(filter.next());
        assertNull(filter.next());
        assertEquals(child.rows.size() + 1, child.reads);
        assertEquals(1, child.closes);
    }

    @Test
    void lifecycleRejectsInvalidReadsAndSupportsEarlyClose() {
        Child child = new Child(row(20, "A"), row(21, "B"));
        FilterOperator filter = filter(child, adult());
        assertThrows(IllegalStateException.class, filter::next);
        filter.open();
        assertThrows(IllegalStateException.class, filter::open);
        assertEquals(1, child.opens);
        assertNotNull(filter.next());
        filter.close();
        filter.close();
        assertEquals(1, child.reads);
        assertEquals(1, child.closes);
        assertThrows(IllegalStateException.class, filter::next);
        assertThrows(IllegalStateException.class, filter::open);
    }

    @Test
    void closeBeforeOpenClosesOwnedChildWithoutOpeningIt() {
        Child child = new Child();
        FilterOperator filter = filter(child, adult());
        filter.close();
        filter.close();
        assertEquals(0, child.opens);
        assertEquals(1, child.closes);
        assertThrows(IllegalStateException.class, filter::open);
        assertThrows(IllegalStateException.class, filter::next);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void childFailureIsPreservedEvenWhenCleanupFails(boolean duringOpen) {
        Child child = new Child();
        RuntimeException primary = new IllegalStateException("Child failed");
        child.closeFailure = new IllegalStateException("Close failed");
        FilterOperator filter = filter(child, adult());
        RuntimeException actual;
        if (duringOpen) {
            child.openFailure = primary;
            actual = assertThrows(IllegalStateException.class, filter::open);
        } else {
            filter.open();
            child.readFailure = primary;
            actual = assertThrows(IllegalStateException.class, filter::next);
        }
        assertSame(primary, actual);
        assertArrayEquals(new Throwable[] {child.closeFailure}, actual.getSuppressed());
        filter.close();
        assertEquals(1, child.closes);
        assertThrows(IllegalStateException.class, filter::next);
        assertThrows(IllegalStateException.class, filter::open);
    }

    @Test
    void predicateFailureClosesChildAndPreservesCleanupError() {
        Child child = new Child(row(20, "A"));
        child.closeFailure = new IllegalStateException("Close failed");
        Predicate invalid = new Comparison(new Column(0, DataType.INTEGER), ComparisonOp.EQ,
                new Literal(new Value(DataType.STRING, "20")));
        FilterOperator filter = filter(child, invalid);
        filter.open();
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, filter::next);
        assertTrue(error.getMessage().contains("matching types"));
        assertArrayEquals(new Throwable[] {child.closeFailure}, error.getSuppressed());
        filter.close();
        assertEquals(1, child.closes);
        assertThrows(IllegalStateException.class, filter::next);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void closeFailureIsReportedOnceAtEofOrExplicitClose(boolean atEof) {
        Child child = new Child();
        child.closeFailure = new IllegalStateException("Close failed");
        FilterOperator filter = filter(child, adult());
        filter.open();
        RuntimeException actual = atEof
                ? assertThrows(IllegalStateException.class, filter::next)
                : assertThrows(IllegalStateException.class, filter::close);
        assertSame(child.closeFailure, actual);
        filter.close();
        assertEquals(1, child.closes);
        assertThrows(IllegalStateException.class, filter::next);
    }

    @Test
    void filtersRealScanUsingNestedIntegerAndStringConditions() {
        DirectoryTableStore store = new DirectoryTableStore(directory);
        store.write(new Table("students", List.of(
                new minidb.catalog.Column("age", DataType.INTEGER),
                new minidb.catalog.Column("name", DataType.STRING))),
                List.of(row(17, "Alice"), row(20, "Bob"), row(22, "Alice"), row(23, "")));
        Predicate name = new Comparison(new Column(1, DataType.STRING), ComparisonOp.EQ,
                new Literal(new Value(DataType.STRING, "Alice")));
        Predicate blank = new Comparison(new Column(1, DataType.STRING), ComparisonOp.EQ,
                new Literal(new Value(DataType.STRING, "")));
        FilterOperator filter = filter(new TableScanOperator(store, "students"),
                new And(adult(), new Or(name, new Not(new Not(blank)))));
        try {
            filter.open();
            assertEquals(row(22, "Alice"), filter.next());
            assertEquals(row(23, ""), filter.next());
            assertNull(filter.next());
        } finally {
            filter.close();
        }
    }
}
