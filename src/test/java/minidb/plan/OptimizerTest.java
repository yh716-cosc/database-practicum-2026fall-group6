package minidb.plan;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import minidb.exec.ExpressionEvaluator;
import minidb.plan.BoundExpression.*;
import minidb.sql.ast.Expression.ComparisonOp;
import minidb.storage.Row;
import minidb.types.DataType;
import minidb.types.Value;

class OptimizerTest {
    private final Optimizer optimizer = new Optimizer();
    private final LogicalOperator scan = new LogicalOperator.Scan("students");
    private final Predicate variable = new Comparison(new Column(0, DataType.INTEGER),
            ComparisonOp.GE, integer(18));

    private static Literal integer(int value) {
        return new Literal(new Value(DataType.INTEGER, value));
    }

    private Predicate optimized(Predicate predicate) {
        LogicalOperator result = optimizer.optimize(new LogicalOperator.Filter(scan, predicate));
        return result instanceof LogicalOperator.Filter filter ? filter.predicate() : new Constant(true);
    }

    @Test void foldsLiteralComparisonsAndRemovesTrueFilters() {
        assertEquals(scan, optimizer.optimize(new LogicalOperator.Filter(scan,
                new Comparison(integer(Integer.MIN_VALUE), ComparisonOp.LT, integer(Integer.MAX_VALUE)))));
        Literal text = new Literal(new Value(DataType.STRING, "O'Brien"));
        assertEquals(new Constant(false), optimized(new Comparison(text, ComparisonOp.NE, text)));
    }

    @Test void simplifiesBooleanExpressionsWithoutChangingResults() {
        List<Predicate> predicates = List.of(
                new And(new Constant(true), variable), new And(variable, new Constant(true)),
                new And(new Constant(false), variable), new And(variable, new Constant(false)),
                new Or(new Constant(false), variable), new Or(variable, new Constant(false)),
                new Or(new Constant(true), variable), new Or(variable, new Constant(true)),
                new Not(new Not(variable)), new Not(new Constant(false)),
                new Not(new Or(new Constant(false), new Not(variable))));
        ExpressionEvaluator evaluator = new ExpressionEvaluator();
        for (Predicate predicate : predicates) {
            Predicate result = optimized(predicate);
            for (int age : List.of(17, 18, 25)) {
                Row row = new Row(List.of(new Value(DataType.INTEGER, age)));
                assertEquals(evaluator.test(predicate, row), evaluator.test(result, row));
            }
            assertEquals(result, optimized(result));
        }
        assertEquals(variable, optimized(new Not(new Not(variable))));
    }

    @Test void keepsFalseFiltersAndPreservesProjectionOrderAndRepeats() {
        assertEquals(new LogicalOperator.Filter(scan, new Constant(false)),
                optimizer.optimize(new LogicalOperator.Filter(scan, new Constant(false))));
        LogicalOperator plan = new LogicalOperator.Project(
                new LogicalOperator.Project(new LogicalOperator.Project(scan, List.of(2, 0, 1)),
                        List.of(2, 0, 2)), List.of(1, 0, 1));
        LogicalOperator expected = new LogicalOperator.Project(scan, List.of(2, 1, 2));
        assertEquals(expected, optimizer.optimize(plan));
        assertEquals(expected, optimizer.optimize(expected));
    }

    @Test void rejectsUnknownAndNullPlans() {
        assertThrows(IllegalArgumentException.class, () -> optimizer.optimize(null));
        assertThrows(IllegalArgumentException.class, () -> optimizer.optimize(new LogicalOperator() {}));
    }

    @Test void mergesLimitsAndIdenticalSortsButKeepsDifferentSorts() {
        var keys = List.of(new LogicalOperator.SortKey(0, true));
        LogicalOperator sorted = new LogicalOperator.Sort(scan, keys);
        assertEquals(sorted, optimizer.optimize(new LogicalOperator.Sort(sorted, keys)));
        var different = new LogicalOperator.Sort(sorted, List.of(new LogicalOperator.SortKey(0, false)));
        assertEquals(different, optimizer.optimize(different));
        assertEquals(new LogicalOperator.Limit(scan, 2), optimizer.optimize(
                new LogicalOperator.Limit(new LogicalOperator.Limit(scan, 5), 2)));
        assertEquals(new LogicalOperator.Limit(scan, 2), optimizer.optimize(
                new LogicalOperator.Limit(new LogicalOperator.Limit(scan, 2), 5)));
        var barrier = new LogicalOperator.Sort(new LogicalOperator.Limit(sorted, 2), keys);
        assertEquals(barrier, optimizer.optimize(barrier));
    }
}
