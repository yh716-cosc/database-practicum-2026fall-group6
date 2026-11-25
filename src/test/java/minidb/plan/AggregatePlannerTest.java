package minidb.plan;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import minidb.catalog.*;
import minidb.plan.LogicalOperator.AggregateCall;
import minidb.sql.SqlParser;
import minidb.sql.ast.Expression.AggregateFunction;
import minidb.types.DataType;

class AggregatePlannerTest {
    private final Catalog catalog = new Catalog() {
        public Collection<Table> tables() { throw new AssertionError("No catalog enumeration needed"); }
        public Table getTable(String name) {
            return switch (name) {
                case "events" -> new Table(name, List.of(new Column("dept", DataType.STRING),
                        new Column("points", DataType.INTEGER)));
                case "depts" -> new Table(name, List.of(new Column("dept", DataType.STRING),
                        new Column("floor", DataType.INTEGER)));
                default -> null;
            };
        }
    };

    private LogicalOperator.Project plan(String sql) {
        return (LogicalOperator.Project) new Planner().plan(new SqlParser().parse(sql), catalog);
    }

    private void assertRejected(String sql, String detail) {
        PlanningException ex = assertThrows(PlanningException.class, () -> plan(sql));
        assertTrue(ex.getMessage().contains(detail), ex.getMessage());
    }

    @Test void globalCountPlansOneAggregateOverScan() {
        var project = plan("SELECT COUNT(*), COUNT(points) FROM events");
        var aggregate = assertInstanceOf(LogicalOperator.Aggregate.class, project.child());
        assertEquals(new LogicalOperator.Scan("events"), aggregate.child());
        assertEquals(List.of(), aggregate.groupIndexes());
        assertEquals(List.of(new AggregateCall(AggregateFunction.COUNT, -1, null, "COUNT(*)"),
                new AggregateCall(AggregateFunction.COUNT, 1, DataType.INTEGER, "COUNT(points)")),
                aggregate.aggregates());
        assertEquals(List.of(0, 1), project.columnIndexes());
    }

    @Test void stageOrderAndHiddenAggregateSlots() {
        var project = plan("SELECT dept FROM events WHERE points > 0 GROUP BY dept "
                + "HAVING COUNT(points) > 0 ORDER BY SUM(points) DESC LIMIT 2");
        var limit = assertInstanceOf(LogicalOperator.Limit.class, project.child());
        var sort = assertInstanceOf(LogicalOperator.Sort.class, limit.child());
        var having = assertInstanceOf(LogicalOperator.Filter.class, sort.child());
        var aggregate = assertInstanceOf(LogicalOperator.Aggregate.class, having.child());
        var where = assertInstanceOf(LogicalOperator.Filter.class, aggregate.child());
        assertEquals(new LogicalOperator.Scan("events"), where.child());
        assertEquals(List.of(0), aggregate.groupIndexes());
        assertEquals(List.of("COUNT(points)", "SUM(points)"),
                aggregate.aggregates().stream().map(AggregateCall::label).toList());
        assertEquals(List.of(new LogicalOperator.SortKey(2, false)), sort.keys());
        var comparison = assertInstanceOf(BoundExpression.Comparison.class, having.predicate());
        assertEquals(new BoundExpression.Column(1, DataType.BIGINT), comparison.left());
        assertEquals(List.of(0), project.columnIndexes());
    }

    @Test void internsEquivalentCallsAndPreservesDuplicateProjections() {
        var project = plan("SELECT SUM(points), dept, SUM(events.points), AVG(points), dept FROM events "
                + "GROUP BY dept, events.dept HAVING SUM(points) > 1 ORDER BY AVG(points)");
        var aggregate = (LogicalOperator.Aggregate) ((LogicalOperator.Filter)
                ((LogicalOperator.Sort) project.child()).child()).child();
        assertEquals(List.of(0), aggregate.groupIndexes());
        assertEquals(List.of(DataType.BIGINT, DataType.DECIMAL),
                aggregate.aggregates().stream().map(AggregateCall::resultType).toList());
        assertEquals(List.of(1, 0, 1, 2, 0), project.columnIndexes());
    }

    @Test void bindsGroupsAfterJoinToJoinedLayout() {
        var project = plan("SELECT d.floor, COUNT(e.points) FROM events e LEFT JOIN depts d ON e.dept = d.dept "
                + "GROUP BY d.floor");
        var aggregate = (LogicalOperator.Aggregate) project.child();
        assertInstanceOf(LogicalOperator.Join.class, aggregate.child());
        assertEquals(List.of(3), aggregate.groupIndexes());
        assertEquals(1, aggregate.aggregates().get(0).argumentIndex());
        assertEquals("COUNT(e.points)", aggregate.aggregates().get(0).label());
    }

    @Test void groupKeysAloneMakeAGroupedQuery() {
        var project = plan("SELECT dept FROM events GROUP BY dept ORDER BY dept");
        var aggregate = (LogicalOperator.Aggregate) ((LogicalOperator.Sort) project.child()).child();
        assertTrue(aggregate.aggregates().isEmpty());
    }

    @Test void minAndMaxKeepInputTypes() {
        var aggregate = (LogicalOperator.Aggregate) plan("SELECT MIN(dept), MAX(points) FROM events").child();
        assertEquals(List.of(DataType.STRING, DataType.INTEGER),
                aggregate.aggregates().stream().map(AggregateCall::resultType).toList());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "SELECT dept, COUNT(*) FROM events|Column dept in SELECT must appear in GROUP BY",
        "SELECT points FROM events GROUP BY dept|Column points in SELECT must appear in GROUP BY",
        "SELECT dept FROM events GROUP BY dept HAVING points > 1|Column points in HAVING must appear",
        "SELECT dept FROM events GROUP BY dept ORDER BY points|Column points in ORDER BY must appear",
        "SELECT dept FROM events HAVING dept = 'A'|HAVING requires GROUP BY or an aggregate",
        "SELECT * FROM events GROUP BY dept|SELECT * is not allowed",
        "SELECT AVG(dept) FROM events|AVG requires an INTEGER column, but dept is STRING",
        "SELECT SUM(dept) FROM events|SUM requires an INTEGER column",
        "SELECT dept FROM events WHERE COUNT(*) > 1 GROUP BY dept|Aggregate functions are not allowed in WHERE",
        "SELECT COUNT(*) FROM events e JOIN depts d ON COUNT(*) = 1|Aggregate functions are not allowed in JOIN ON",
        "SELECT COUNT(nope) FROM events|Unknown column: nope",
        "SELECT COUNT(dept) FROM events e JOIN depts d ON e.dept = d.dept|Ambiguous column: dept",
        "SELECT COUNT(*) FROM events e JOIN depts d ON e.dept = d.dept GROUP BY dept|Ambiguous column: dept",
        "SELECT COUNT(*) FROM events GROUP BY x.dept|Unknown table qualifier: x",
        "SELECT COUNT(*) FROM events HAVING MIN(dept) > 'A'|STRING comparison only supports",
        "SELECT COUNT(*) FROM events HAVING MIN(dept) = 1|Comparison requires matching types",
    })
    void rejectsInvalidAggregateQueries(String sql, String detail) {
        assertRejected(sql, detail);
    }

    @Test void numericHavingComparesAcrossResultTypes() {
        var project = plan("SELECT dept FROM events GROUP BY dept HAVING AVG(points) > 80 AND COUNT(*) >= 2");
        var having = (LogicalOperator.Filter) project.child();
        var and = assertInstanceOf(BoundExpression.And.class, having.predicate());
        var avg = (BoundExpression.Comparison) and.left();
        assertEquals(DataType.DECIMAL, avg.left().type());
        assertEquals(DataType.INTEGER, avg.right().type());
    }

    @Test void optimizerKeepsHavingAboveTheAggregate() {
        var optimized = new Optimizer().optimize(plan(
                "SELECT dept FROM events WHERE 1 = 1 GROUP BY dept HAVING COUNT(*) > 1 AND 2 = 2"));
        var project = assertInstanceOf(LogicalOperator.Project.class, optimized);
        var having = assertInstanceOf(LogicalOperator.Filter.class, project.child());
        assertInstanceOf(BoundExpression.Comparison.class, having.predicate());
        var aggregate = assertInstanceOf(LogicalOperator.Aggregate.class, having.child());
        assertEquals(new LogicalOperator.Scan("events"), aggregate.child());
    }
}
