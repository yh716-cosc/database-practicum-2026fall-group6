package minidb.sql;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import minidb.sql.ast.*;
import minidb.sql.ast.Expression.Aggregate;
import minidb.sql.ast.Expression.AggregateFunction;

class AggregateParserTest {
    private final SqlParser parser = new SqlParser();

    private Query parse(String sql) {
        return (Query) parser.parse(sql);
    }

    private void assertRejects(String sql, String detail) {
        SqlParseException ex = assertThrows(SqlParseException.class, () -> parser.parse(sql));
        assertTrue(ex.getMessage().contains(detail), ex.getMessage());
    }

    private static Aggregate agg(AggregateFunction function, Expression argument) {
        return new Aggregate(function, argument);
    }

    @Test void parsesAllAggregateFormsCaseInsensitively() {
        Query query = parse("select Count(*), count(s.id), SUM(age), avg(age), Min(name), MAX(name) from s");
        assertEquals(List.of(
                agg(AggregateFunction.COUNT, new Expression.Star()),
                agg(AggregateFunction.COUNT, new ColumnRef("s", "id")),
                agg(AggregateFunction.SUM, new ColumnRef(null, "age")),
                agg(AggregateFunction.AVG, new ColumnRef(null, "age")),
                agg(AggregateFunction.MIN, new ColumnRef(null, "name")),
                agg(AggregateFunction.MAX, new ColumnRef(null, "name"))),
                query.select().stream().map(SelectItem::expression).toList());
        assertTrue(query.groupBy().isEmpty());
        assertNull(query.having());
    }

    @Test void parsesClausesInOrder() {
        Query query = parse("SELECT dept, COUNT(*) FROM e x JOIN d ON x.id = d.id WHERE points >= 5 "
                + "GROUP BY dept, x.id HAVING COUNT(*) >= 2 AND NOT MAX(points) IS NULL "
                + "ORDER BY SUM(points) DESC, dept LIMIT 3;");
        assertEquals(List.of(new ColumnRef(null, "dept"), new ColumnRef("x", "id")), query.groupBy());
        assertEquals(new Expression.And(
                new Expression.Comparison(agg(AggregateFunction.COUNT, new Expression.Star()),
                        Expression.ComparisonOp.GE, new Expression.IntLiteral(2)),
                new Expression.Not(new Expression.IsNull(agg(AggregateFunction.MAX,
                        new ColumnRef(null, "points")), false))), query.having());
        assertEquals(List.of(new OrderBy(agg(AggregateFunction.SUM, new ColumnRef(null, "points")), false),
                new OrderBy(new ColumnRef(null, "dept"), true)), query.orderBy());
        assertNull(query.orderBy().get(0).column());
        assertEquals(3, query.limit());
        assertNotNull(query.where());
        assertEquals("x", query.from().get(0).alias());
    }

    @Test void aggregateNamesStayColumnsOutsideCallPosition() {
        Query query = parse("SELECT count, sum FROM t WHERE count = 1 GROUP BY count ORDER BY sum");
        assertEquals(new ColumnRef(null, "count"), query.select().get(0).expression());
        assertEquals(List.of(new ColumnRef(null, "count")), query.groupBy());
        assertEquals(new ColumnRef(null, "sum"), query.orderBy().get(0).key());
        // A table alias named like a clause keyword still ends at GROUP/HAVING.
        assertNull(parse("SELECT a FROM t GROUP BY a").from().get(0).alias());
        assertNotNull(parse("SELECT COUNT(*) FROM t HAVING COUNT(*) > 0").having());
    }

    @Test void aggregatesParseInWhereForPlannerRejection() {
        assertInstanceOf(Expression.Comparison.class, parse("SELECT a FROM t WHERE COUNT(*) > 1").where());
    }

    @Test void rejectsMalformedCalls() {
        assertRejects("SELECT SUM(*) FROM t", "only COUNT accepts *");
        assertRejects("SELECT COUNT() FROM t", "COUNT requires one argument");
        assertRejects("SELECT COUNT(a, b) FROM t", "takes exactly one argument");
        assertRejects("SELECT COUNT(DISTINCT a) FROM t", "DISTINCT aggregates are not supported");
        assertRejects("SELECT SUM(MAX(a)) FROM t", "nested function calls are not supported");
        assertRejects("SELECT SUM(1) FROM t", "aggregate argument must be a column");
        assertRejects("SELECT COUNT('x') FROM t", "must be a column or *");
        assertRejects("SELECT COUNT(* FROM t", "expected ')'");
        assertRejects("SELECT upper(a) FROM t", "unsupported function upper");
        assertRejects("SELECT a FROM t WHERE upper(a) = 'A'", "unsupported expression");
        assertRejects("SELECT COUNT(*) AS c FROM t", "column aliases are not supported");
        assertRejects("SELECT COUNT(*) + 1 FROM t", "unexpected character '+'");
        assertRejects("SELECT COUNT(*) 1 FROM t", "expected FROM");
    }

    @Test void rejectsMalformedGroupingClauses() {
        assertRejects("SELECT a FROM t GROUP a", "expected BY");
        assertRejects("SELECT a FROM t GROUP BY", "expected GROUP BY column");
        assertRejects("SELECT a FROM t GROUP BY 1", "GROUP BY ordinals are not supported");
        assertRejects("SELECT a FROM t GROUP BY COUNT(a)", "GROUP BY accepts only column references");
        assertRejects("SELECT a FROM t GROUP BY a,", "expected GROUP BY column");
        assertRejects("SELECT a FROM t HAVING", "expected column, integer, or string");
        assertRejects("SELECT a FROM t HAVING COUNT(*)", "expected comparison operator");
        assertRejects("SELECT a FROM t GROUP BY a WHERE a = 1", "expected end of input");
        assertRejects("SELECT a FROM t HAVING a = 1 GROUP BY a", "misplaced GROUP BY");
        assertRejects("SELECT a FROM t ORDER BY a HAVING a = 1", "misplaced HAVING");
        assertRejects("SELECT a FROM t GROUP BY a GROUP BY a", "misplaced GROUP BY");
    }
}
