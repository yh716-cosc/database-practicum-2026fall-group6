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
    }
}
