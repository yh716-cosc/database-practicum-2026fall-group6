package minidb.exec;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import minidb.catalog.Column;
import minidb.catalog.Table;
import minidb.plan.BoundExpression;
import minidb.sql.ast.Expression.ComparisonOp;
import minidb.storage.DirectoryTableStore;
import minidb.storage.Row;
import minidb.types.DataType;
import minidb.types.Value;

class ProjectOperatorTest {
    @TempDir Path directory;

    private static Row row(int age, String name) {
        return new Row(List.of(new Value(DataType.INTEGER, age), new Value(DataType.STRING, name)));
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
    void reordersAndRepeatsColumnsWithoutChangingInput() {
        Row input = row(20, "Alice");
        Child child = new Child(input, input, row(21, "Bob"));
        List<Integer> indexes = new ArrayList<>(List.of(1, 0, 1));
        ProjectOperator project = new ProjectOperator(child, indexes);
        indexes.clear();
        project.open();
        assertEquals(0, child.reads);
        Row output = project.next();
        assertEquals(1, child.reads);
        assertEquals(new Row(List.of(input.values().get(1), input.values().get(0),
                input.values().get(1))), output);
        assertNotSame(input, output);
        assertSame(input.values().get(1), output.values().get(0));
        assertSame(output.values().get(0), output.values().get(2));
        assertEquals(row(20, "Alice"), input);
        assertThrows(UnsupportedOperationException.class, () -> output.values().clear());
        assertEquals(output, project.next());
        assertEquals("Bob", project.next().values().get(0).raw());
        assertNull(project.next());
        assertNull(project.next());
        assertEquals(4, child.reads);
        assertEquals(1, child.closes);
        assertThrows(IllegalStateException.class, project::open);
        project.close();
        project.close();
        assertEquals(1, child.closes);
        assertThrows(IllegalStateException.class, project::next);
    }

    @Test
    void selectsSubsetAndPreservesTypesAndRowOrder() {
        Child child = new Child(row(1, "00123"), row(2, ""));
        ProjectOperator project = new ProjectOperator(child, List.of(1));
        project.open();
        assertEquals(new Row(List.of(new Value(DataType.STRING, "00123"))), project.next());
        assertEquals(new Row(List.of(new Value(DataType.STRING, ""))), project.next());
        assertNull(project.next());
    }

    @Test
    void emptyInputClosesAtEof() {
        Child child = new Child();
        ProjectOperator project = new ProjectOperator(child, List.of(0));
        project.open();
        assertNull(project.next());
        assertNull(project.next());
        assertEquals(1, child.reads);
        assertEquals(1, child.closes);
    }

    @Test
    void lifecycleAndEarlyCloseDoNotConsumeRemainingRows() {
        Child child = new Child(row(1, "A"), row(2, "B"));
        ProjectOperator project = new ProjectOperator(child, List.of(0));
        assertThrows(IllegalStateException.class, project::next);
        project.open();
        assertThrows(IllegalStateException.class, project::open);
        assertEquals(1, child.opens);
        assertNotNull(project.next());
        project.close();
        project.close();
        assertEquals(1, child.closes);
        assertEquals(1, child.reads);
        assertThrows(IllegalStateException.class, project::next);
        assertThrows(IllegalStateException.class, project::open);
    }

    @Test
    void closesBeforeOpenWithoutOpeningChild() {
        Child child = new Child();
        ProjectOperator project = new ProjectOperator(child, List.of(0));
        project.close();
        project.close();
        assertEquals(0, child.opens);
        assertEquals(1, child.closes);
        assertThrows(IllegalStateException.class, project::open);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void childFailurePreservesOriginalErrorAndCleanupFailure(boolean duringOpen) {
        Child child = new Child();
        RuntimeException primary = new IllegalStateException("Child failed");
        child.closeFailure = new IllegalStateException("Close failed");
        ProjectOperator project = new ProjectOperator(child, List.of(0));
        RuntimeException actual;
        if (duringOpen) {
            child.openFailure = primary;
            actual = assertThrows(IllegalStateException.class, project::open);
        } else {
            project.open();
            child.readFailure = primary;
            actual = assertThrows(IllegalStateException.class, project::next);
        }
        assertSame(primary, actual);
        assertArrayEquals(new Throwable[] {child.closeFailure}, actual.getSuppressed());
        project.close();
        assertEquals(1, child.closes);
        assertThrows(IllegalStateException.class, project::next);
        assertThrows(IllegalStateException.class, project::open);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 2})
    void invalidIndexClosesChildAndPreservesCleanupFailure(int index) {
        Child child = new Child(row(20, "A"));
        child.closeFailure = new IllegalStateException("Close failed");
        ProjectOperator project = new ProjectOperator(child, List.of(index));
        project.open();
        RuntimeException failure = assertThrows(IndexOutOfBoundsException.class, project::next);
        assertArrayEquals(new Throwable[] {child.closeFailure}, failure.getSuppressed());
        project.close();
        assertEquals(1, child.closes);
        assertThrows(IllegalStateException.class, project::next);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void closeFailureIsReportedOnce(boolean atEof) {
        Child child = new Child();
        child.closeFailure = new IllegalStateException("Close failed");
        ProjectOperator project = new ProjectOperator(child, List.of(0));
        project.open();
        RuntimeException actual = atEof
                ? assertThrows(IllegalStateException.class, project::next)
                : assertThrows(IllegalStateException.class, project::close);
        assertSame(child.closeFailure, actual);
        project.close();
        assertEquals(1, child.closes);
        assertThrows(IllegalStateException.class, project::next);
    }

    @Test
    void scanFilterProjectCanFilterOnAColumnAbsentFromOutput() {
        DirectoryTableStore store = new DirectoryTableStore(directory);
        store.write(new Table("students", List.of(new Column("age", DataType.INTEGER),
                new Column("name", DataType.STRING))),
                List.of(row(17, "Young"), row(18, "Alice"), row(22, "Bob")));
        BoundExpression.Predicate adult = new BoundExpression.Comparison(
                new BoundExpression.Column(0, DataType.INTEGER), ComparisonOp.GE,
                new BoundExpression.Literal(new Value(DataType.INTEGER, 18)));
        ProjectOperator project = new ProjectOperator(new FilterOperator(
                new TableScanOperator(store, "students"), adult, new ExpressionEvaluator()), List.of(1));
        try {
            project.open();
            assertEquals(new Row(List.of(new Value(DataType.STRING, "Alice"))), project.next());
            assertEquals(new Row(List.of(new Value(DataType.STRING, "Bob"))), project.next());
            assertNull(project.next());
        } finally {
            project.close();
        }
    }
}
