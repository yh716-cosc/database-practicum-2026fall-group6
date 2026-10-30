package minidb.plan;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import minidb.catalog.*;
import minidb.sql.SqlParser;
import minidb.sql.ast.*;
import minidb.types.*;

class PlannerTest {
    private final Planner planner = new Planner();
    private final Catalog catalog = new Catalog() {
        public Collection<Table> tables() { throw new AssertionError("No catalog enumeration needed"); }
        public Table getTable(String name) {
            return name.equals("students") ? new Table(name, List.of(
                    new Column("id", DataType.INTEGER), new Column("name", DataType.STRING),
                    new Column("age", DataType.INTEGER))) : null;
        }
    };

    private LogicalOperator.Project plan(String sql) {
        return (LogicalOperator.Project) planner.plan(new SqlParser().parse(sql), catalog);
    }

    @Test void bindsSortToInputColumnsAndLimitsBeforeProjection() {
        var project = plan("SELECT name FROM students WHERE age > 0 ORDER BY age DESC, id LIMIT 3");
        assertEquals(List.of(1), project.columnIndexes());
        var limit = assertInstanceOf(LogicalOperator.Limit.class, project.child());
        assertEquals(3, limit.count());
        var sort = assertInstanceOf(LogicalOperator.Sort.class, limit.child());
        assertEquals(List.of(new LogicalOperator.SortKey(2, false),
                new LogicalOperator.SortKey(0, true)), sort.keys());
        assertInstanceOf(LogicalOperator.Filter.class, sort.child());
        Query parsed = (Query) new SqlParser().parse("SELECT id FROM students");
        assertThrows(PlanningException.class, () -> planner.plan(new Query(parsed.select(),
                parsed.from(), parsed.joins(), null, List.of(), -1), catalog));
    }

    @Test void bindsFilterBeforeProjectionAndPreservesSelectionOrder() {
        var result = plan("SELECT name, students.id, name FROM students WHERE age >= 18");
        assertEquals(List.of(1, 0, 1), result.columnIndexes());
        var filter = assertInstanceOf(LogicalOperator.Filter.class, result.child());
        assertEquals(new LogicalOperator.Scan("students"), filter.child());
        assertEquals(new BoundExpression.Comparison(new BoundExpression.Column(2, DataType.INTEGER),
                Expression.ComparisonOp.GE, new BoundExpression.Literal(new Value(DataType.INTEGER, 18))), filter.predicate());
        assertThrows(UnsupportedOperationException.class, () -> result.columnIndexes().clear());
    }

    @Test void expandsStarWithoutAddingFilter() {
        assertEquals(new LogicalOperator.Project(new LogicalOperator.Scan("students"), List.of(0, 1, 2)),
                plan("SELECT * FROM students"));
    }

    @Test void bindsBooleanTreeAndColumnComparisons() {
        var result = plan("SELECT id FROM students WHERE NOT (age < id OR name = '') AND 1 = 1");
        var predicate = ((LogicalOperator.Filter) result.child()).predicate();
        var and = assertInstanceOf(BoundExpression.And.class, predicate);
        var not = assertInstanceOf(BoundExpression.Not.class, and.left());
        var or = assertInstanceOf(BoundExpression.Or.class, not.child());
        var comparison = assertInstanceOf(BoundExpression.Comparison.class, or.left());
        assertEquals(new BoundExpression.Column(0, DataType.INTEGER), comparison.right());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT missing FROM students", "SELECT Students.id FROM students", "SELECT ID FROM students",
        "SELECT * FROM missing", "SELECT id FROM students WHERE age = '18'",
        "SELECT id FROM students WHERE name > 'Alice'", "SELECT id FROM students WHERE id = name",
        "SELECT id FROM students WHERE 1 = 1 OR missing = 0",
        "SELECT id FROM students WHERE 1 = 0 AND age = '18'"
    })
    void rejectsInvalidNamesAndTypesEvenInShortCircuitedBranches(String sql) {
        assertThrows(PlanningException.class, () -> plan(sql));
    }

    @ParameterizedTest
    @ValueSource(strings = {"=", "<>", "<", "<=", ">", ">="})
    void acceptsAllIntegerComparisons(String op) {
        assertDoesNotThrow(() -> plan("SELECT id FROM students WHERE age " + op + " -2147483648"));
    }

    @Test void validatesDirectlyConstructedAst() {
        var select = List.of(new SelectItem(new Expression.Star(), null));
        var from = List.of(new TableRef("students", null));
        List<Query> invalid = List.of(
            new Query(select, List.of(), List.of(), null),
            new Query(select, List.of(from.get(0), from.get(0)), List.of(), null),
            new Query(select, List.of(new TableRef("students", "")), List.of(), null),
            new Query(select, from, List.of(new Join(from.get(0), null)), null),
            new Query(List.of(), from, List.of(), null),
            new Query(List.of(select.get(0), select.get(0)), from, List.of(), null),
            new Query(List.of(new SelectItem(new ColumnRef(null, "id"), "x")), from, List.of(), null),
            new Query(select, from, List.of(), new Expression.IntLiteral(1)),
            new Query(select, from, List.of(), new Expression.Not(new Expression.Star())));
        for (Query query : invalid) assertThrows(PlanningException.class, () -> planner.plan(query, catalog));
        assertThrows(PlanningException.class, () -> planner.plan(new Statement() {}, catalog));
    }

    @Test void rejectsExcessiveFlatPredicateDepthWithoutStackOverflow() {
        assertThrows(PlanningException.class,
                () -> plan("SELECT id FROM students WHERE " + "id = 1 AND ".repeat(1000) + "id = 1"));
    }
}
