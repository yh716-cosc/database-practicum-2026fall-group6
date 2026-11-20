package minidb.plan;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.Test;

import minidb.catalog.Catalog;
import minidb.catalog.Column;
import minidb.catalog.Table;
import minidb.index.KeyRange;
import minidb.sql.SqlParser;
import minidb.types.DataType;

class IndexSelectionTest {
    private final Table students = new Table("students", List.of(new Column("id", DataType.INTEGER),
            new Column("name", DataType.STRING), new Column("age", DataType.INTEGER),
            new Column("score", DataType.INTEGER)));
    private final Table courses = new Table("courses", List.of(new Column("sid", DataType.INTEGER)));
    private final Catalog catalog = new Catalog() {
        @Override public Collection<Table> tables() { return List.of(students, courses); }
        @Override public Table getTable(String name) {
            return name.equals("students") ? students : name.equals("courses") ? courses : null;
        }
        @Override public List<String> indexedColumns(String table) {
            return table.equals("students") ? List.of("age", "id") : List.of("sid");
        }
    };

    private LogicalOperator optimize(String sql, Optimizer optimizer) {
        return optimizer.optimize(new Planner().plan(new SqlParser().parse(sql), catalog));
    }

    /** The access path under the Filter that sits below Project (and any Sort or Limit). */
    private LogicalOperator input(String sql) {
        LogicalOperator plan = optimize(sql, new Optimizer(catalog));
        while (true) {
            if (plan instanceof LogicalOperator.Filter filter) return filter.child();
            if (plan instanceof LogicalOperator.Project p) plan = p.child();
            else if (plan instanceof LogicalOperator.Sort s) plan = s.child();
            else if (plan instanceof LogicalOperator.Limit l) plan = l.child();
            else throw new AssertionError("No filter in " + plan);
        }
    }

    private static LogicalOperator.IndexScan index(String column, long min, long max) {
        return new LogicalOperator.IndexScan("students", column, new KeyRange(min, max));
    }

    @Test void usesIndexForComparisonsWithLiterals() {
        assertEquals(index("age", 20, 20), input("SELECT * FROM students WHERE age = 20"));
        assertEquals(index("age", 18, Integer.MAX_VALUE), input("SELECT name FROM students WHERE age >= 18"));
        assertEquals(index("age", 19, Integer.MAX_VALUE), input("SELECT name FROM students WHERE 18 < age"));
        assertEquals(index("id", Integer.MIN_VALUE, 4), input("SELECT name FROM students WHERE id < 5"));
        assertEquals(index("age", Integer.MIN_VALUE, Integer.MIN_VALUE - 1L),
                input("SELECT name FROM students WHERE age < -2147483648"));
    }

    @Test void intersectsConjunctsAndKeepsTheFullFilter() {
        LogicalOperator plan = optimize("SELECT name FROM students WHERE age >= 18 AND score >= 85 AND age < 30",
                new Optimizer(catalog));
        LogicalOperator.Filter filter = (LogicalOperator.Filter) ((LogicalOperator.Project) plan).child();
        assertEquals(index("age", 18, 29), filter.child());
        assertEquals(optimize("SELECT name FROM students WHERE age >= 18 AND score >= 85 AND age < 30",
                new Optimizer()), new LogicalOperator.Project(new LogicalOperator.Filter(
                        new LogicalOperator.Scan("students"), filter.predicate()), List.of(1)));
        assertEquals(index("id", 3, 3), input("SELECT * FROM students WHERE age > 10 AND id = 3"));
        assertEquals(index("age", 5, 2), input("SELECT * FROM students WHERE age > 4 AND age < 3"));
    }

    @Test void indexScanSitsBelowSortAndLimit() {
        assertEquals(index("age", 21, Integer.MAX_VALUE),
                input("SELECT name FROM students WHERE age > 20 ORDER BY name DESC LIMIT 2"));
    }

    @Test void fallsBackToScanWhenNoConjunctIsUsable() {
        LogicalOperator scan = new LogicalOperator.Scan("students");
        for (String where : List.of("age = 1 OR age = 2", "NOT age = 1", "age <> 3", "score = 90",
                "name = 'Bob'", "age = id", "age IS NULL", "(age = 1 OR id = 2) AND score > 3")) {
            assertEquals(scan, input("SELECT * FROM students WHERE " + where), where);
        }
        assertEquals(scan, input("SELECT * FROM students WHERE age = 20", new Optimizer()));
    }

    private LogicalOperator input(String sql, Optimizer optimizer) {
        LogicalOperator plan = optimize(sql, optimizer);
        return ((LogicalOperator.Filter) ((LogicalOperator.Project) plan).child()).child();
    }

    @Test void leavesFiltersAboveJoinsAlone() {
        LogicalOperator plan = optimize("SELECT * FROM students JOIN courses ON id = sid WHERE age = 20",
                new Optimizer(catalog));
        LogicalOperator.Filter filter = (LogicalOperator.Filter) ((LogicalOperator.Project) plan).child();
        assertInstanceOf(LogicalOperator.Join.class, filter.child());
    }
}
