package minidb.exec;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import minidb.plan.BoundExpression;
import minidb.plan.BoundExpression.*;
import minidb.sql.ast.Expression.ComparisonOp;
import minidb.storage.Row;
import minidb.types.DataType;
import minidb.types.Value;

class ExpressionEvaluatorTest {
    private final ExpressionEvaluator evaluator = new ExpressionEvaluator();
    private final Row empty = new Row(List.of());

    private static Literal integer(int value) {
        return new Literal(new Value(DataType.INTEGER, value));
    }

    private static Literal string(String value) {
        return new Literal(new Value(DataType.STRING, value));
    }

    private static Predicate truth(boolean value) {
        return new Comparison(integer(1), ComparisonOp.EQ, integer(value ? 1 : 0));
    }

    @Test
    void readsTypedLiteralsAndColumns() {
        Value number = new Value(DataType.INTEGER, 20);
        Value name = new Value(DataType.STRING, "Alice");
        Row row = new Row(List.of(number, name));
        assertSame(number, evaluator.evaluate(new Literal(number), empty));
        assertSame(name, evaluator.evaluate(new Literal(name), empty));
        assertSame(number, evaluator.evaluate(new Column(0, DataType.INTEGER), row));
        assertSame(name, evaluator.evaluate(new Column(1, DataType.STRING), row));
    }

    @ParameterizedTest
    @CsvSource({
        "EQ, false, true, false", "NE, true, false, true",
        "LT, true, false, false", "LE, true, true, false",
        "GT, false, false, true", "GE, false, true, true"
    })
    void comparesIntegersWithoutOverflow(ComparisonOp op, boolean less,
            boolean equal, boolean greater) {
        assertEquals(less, evaluator.test(new Comparison(
                integer(Integer.MIN_VALUE), op, integer(Integer.MAX_VALUE)), empty));
        assertEquals(equal, evaluator.test(new Comparison(integer(-7), op, integer(-7)), empty));
        assertEquals(greater, evaluator.test(new Comparison(
                integer(Integer.MAX_VALUE), op, integer(Integer.MIN_VALUE)), empty));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Alice", "00123", "NULL", " leading and trailing ",
            "O'Brien", "a,b", "caf\u00e9", "\u03bb"})
    void comparesStringContentsRatherThanReferences(String text) {
        Literal left = string(new String(text));
        Literal right = string(new String(text));
        assertTrue(evaluator.test(new Comparison(left, ComparisonOp.EQ, right), empty));
        assertFalse(evaluator.test(new Comparison(left, ComparisonOp.NE, right), empty));
    }

    @ParameterizedTest
    @ValueSource(strings = {"alice", "Alice ", " Alice", "ALICE"})
    void preservesCaseAndWhitespace(String other) {
        assertFalse(evaluator.test(new Comparison(string("Alice"), ComparisonOp.EQ, string(other)), empty));
        assertTrue(evaluator.test(new Comparison(string("Alice"), ComparisonOp.NE, string(other)), empty));
    }

    @Test
    void doesNotNormalizeUnicode() {
        assertFalse(evaluator.test(new Comparison(
                string("\u00e9"), ComparisonOp.EQ, string("e\u0301")), empty));
    }

    @Test
    void evaluatesNestedConditionsAndColumnComparisons() {
        Row row = new Row(List.of(new Value(DataType.INTEGER, 20),
                new Value(DataType.INTEGER, 18), new Value(DataType.STRING, "Alice"),
                new Value(DataType.STRING, new String("Alice"))));
        Predicate age = new Comparison(new Column(0, DataType.INTEGER), ComparisonOp.GE,
                new Column(1, DataType.INTEGER));
        Predicate name = new Comparison(new Column(2, DataType.STRING), ComparisonOp.EQ,
                new Column(3, DataType.STRING));
        assertTrue(evaluator.test(new And(age, new Or(new Not(name), name)), row));
        assertFalse(evaluator.test(new Not(new And(age, name)), row));
    }

    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void followsBooleanTruthTables(boolean left, boolean right) {
        assertEquals(left && right, evaluator.test(new And(truth(left), truth(right)), empty));
        assertEquals(left || right, evaluator.test(new Or(truth(left), truth(right)), empty));
        assertEquals(!left, evaluator.test(new Not(truth(left)), empty));
    }

    @Test
    void shortCircuitsOnlyWhenTheLeftOperandDeterminesTheResult() {
        // Accessing a missing column exposes any unexpected right-side evaluation.
        Predicate unreadable = new Comparison(new Column(0, DataType.INTEGER),
                ComparisonOp.EQ, integer(1));
        assertFalse(evaluator.test(new And(truth(false), unreadable), empty));
        assertTrue(evaluator.test(new Or(truth(true), unreadable), empty));
        assertThrows(IndexOutOfBoundsException.class,
                () -> evaluator.test(new And(truth(true), unreadable), empty));
        assertThrows(IndexOutOfBoundsException.class,
                () -> evaluator.test(new Or(truth(false), unreadable), empty));
    }

    @ParameterizedTest
    @EnumSource(ComparisonOp.class)
    void rejectsMixedTypesWithoutCoercion(ComparisonOp op) {
        assertThrows(IllegalArgumentException.class,
                () -> evaluator.test(new Comparison(integer(18), op, string("18")), empty));
        assertThrows(IllegalArgumentException.class,
                () -> evaluator.test(new Comparison(string("18"), op, integer(18)), empty));
    }

    @ParameterizedTest
    @EnumSource(value = ComparisonOp.class, names = {"LT", "LE", "GT", "GE"})
    void rejectsStringOrdering(ComparisonOp op) {
        assertThrows(IllegalArgumentException.class,
                () -> evaluator.test(new Comparison(string("Alice"), op, string("Bob")), empty));
    }

    @Test
    void rejectsUnknownExpressionNodes() {
        Scalar unknown = new Scalar() {
            public DataType type() { return DataType.INTEGER; }
        };
        assertThrows(IllegalArgumentException.class, () -> evaluator.evaluate(unknown, empty));
        assertThrows(IllegalArgumentException.class,
                () -> evaluator.test(new BoundExpression.Predicate() {}, empty));
    }
}
