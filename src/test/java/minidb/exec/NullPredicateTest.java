package minidb.exec;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import minidb.plan.*;
import minidb.plan.BoundExpression.*;
import minidb.sql.ast.Expression.ComparisonOp;
import minidb.types.*;

class NullPredicateTest {
    final ExpressionEvaluator evaluator = new ExpressionEvaluator();
    final Predicate unknown = new Comparison(new Literal(Value.nullValue(DataType.INTEGER)),
            ComparisonOp.EQ, new Literal(new Value(DataType.INTEGER, 1)));

    @Test void allThreeValuedBooleanCombinations() {
        Predicate[] predicates = {new Constant(true), new Constant(false), unknown};
        TruthValue T = TruthValue.TRUE, F = TruthValue.FALSE, U = TruthValue.UNKNOWN;
        TruthValue[][] and = {{T,F,U}, {F,F,F}, {U,F,U}};
        TruthValue[][] or = {{T,T,T}, {T,F,U}, {T,U,U}};
        TruthValue[] not = {F,T,U};
        for (int i = 0; i < 3; i++) {
            assertEquals(not[i], evaluator.truth(new Not(predicates[i]), null));
            for (int j = 0; j < 3; j++) {
                assertEquals(and[i][j], evaluator.truth(new And(predicates[i], predicates[j]), null));
                assertEquals(or[i][j], evaluator.truth(new Or(predicates[i], predicates[j]), null));
            }
        }
        assertFalse(evaluator.test(unknown, null));
        assertTrue(evaluator.test(new IsNull(new Literal(Value.nullValue(DataType.STRING)), false), null));
        assertFalse(evaluator.test(new IsNull(new Literal(Value.nullValue(DataType.STRING)), true), null));
    }

    @Test void optimizerDoesNotTurnUnknownIntoFalseBeforeNot() {
        var plan = new LogicalOperator.Filter(new LogicalOperator.Scan("t"), new Not(unknown));
        var optimized = (LogicalOperator.Filter) new Optimizer().optimize(plan);
        assertEquals(TruthValue.UNKNOWN, evaluator.truth(optimized.predicate(), null));
        var check = new IsNull(new Column(0, DataType.INTEGER), false);
        assertEquals(check, ((LogicalOperator.Filter) new Optimizer().optimize(
                new LogicalOperator.Filter(new LogicalOperator.Scan("t"), check))).predicate());

import org.junit.jupiter.api.Test;
import minidb.plan.BoundExpression;
import minidb.sql.ast.Expression.ComparisonOp;
import minidb.types.DataType;
import minidb.types.Value;

class NullPredicateTest {
    @Test void evaluatesEveryThreeValuedBooleanCombination() {
        TruthValue t = TruthValue.TRUE, f = TruthValue.FALSE, u = TruthValue.UNKNOWN;
        TruthValue[] values = {t, f, u};
        TruthValue[][] and = {{t, f, u}, {f, f, f}, {u, f, u}};
        TruthValue[][] or = {{t, t, t}, {t, f, u}, {t, u, u}};
        TruthValue[] not = {f, t, u};
        ExpressionEvaluator evaluator = new ExpressionEvaluator();
        for (int i = 0; i < values.length; i++) {
            var left = predicate(values[i]);
            assertEquals(not[i], evaluator.truth(new BoundExpression.Not(left), null));
            for (int j = 0; j < values.length; j++) {
                var right = predicate(values[j]);
                var conjunction = new BoundExpression.And(left, right);
                var disjunction = new BoundExpression.Or(left, right);
                assertEquals(and[i][j], evaluator.truth(conjunction, null));
                assertEquals(or[i][j], evaluator.truth(disjunction, null));
                assertEquals(and[i][j] == t, evaluator.test(conjunction, null));
                assertEquals(or[i][j] == t, evaluator.test(disjunction, null));
            }
        }
    }

    private BoundExpression.Predicate predicate(TruthValue truth) {
        Value left = new Value(DataType.INTEGER, switch (truth) {
            case TRUE -> 1;
            case FALSE -> 2;
            case UNKNOWN -> null;
        });
        return new BoundExpression.Comparison(new BoundExpression.Literal(left), ComparisonOp.EQ,
                new BoundExpression.Literal(new Value(DataType.INTEGER, 1)));
    }
}
