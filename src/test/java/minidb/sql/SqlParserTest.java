package minidb.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import minidb.sql.ast.ColumnRef;
import minidb.sql.ast.Expression;
import minidb.sql.ast.Query;
import minidb.sql.ast.SelectItem;
import minidb.sql.ast.TableRef;

class SqlParserTest {
    private final SqlParser parser = new SqlParser();

    @Test void parsesOrderingDirectionsAndLimitWithoutReservingColumnNames() {
        Query query = parse("select name from students where age > 0 "
                + "order by students.age desc, name asc, id limit 2;");
        assertEquals(List.of(
                new minidb.sql.ast.OrderBy(new ColumnRef("students", "age"), false),
                new minidb.sql.ast.OrderBy(new ColumnRef(null, "name"), true),
                new minidb.sql.ast.OrderBy(new ColumnRef(null, "id"), true)), query.orderBy());
        assertEquals(2, query.limit());
        assertEquals(new ColumnRef(null, "limit"), parse("SELECT limit FROM t LIMIT 0")
                .select().get(0).expression());
    }

    @Test
    void parsesTheFilteringExample() {
        Query query = parse("SELECT id, name FROM students WHERE age >= 18 AND name <> '';");
        assertEquals(query(
                List.of(item("id"), item("name")),
                "students",
                new Expression.And(
                        comparison("age", Expression.ComparisonOp.GE, new Expression.IntLiteral(18)),
                        comparison("name", Expression.ComparisonOp.NE, new Expression.StringLiteral("")))),
                query);
    }

    @Test
    void selectListPreservesOrderDuplicatesAndCase() {
        Query query = parse("select Student_Id, Student_Id, name FROM Students");
        assertEquals(List.of(item("Student_Id"), item("Student_Id"), item("name")), query.select());
        assertEquals("Students", query.from().get(0).name());
        assertNull(query.from().get(0).alias());
        assertNull(query.where());
        assertTrue(query.joins().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> query.select().add(item("extra")));
        assertThrows(UnsupportedOperationException.class, () -> query.from().clear());
        assertThrows(UnsupportedOperationException.class, () -> query.joins().add(null));
    }

    @Test
    void starQualifiedColumnsAndOptionalSemicolon() {
        Query star = parse("SELECT * FROM students");
        assertEquals(List.of(new SelectItem(new Expression.Star(), null)), star.select());
        assertNull(star.select().get(0).alias());
        assertNull(star.where());

        Query qualified = parse("SELECT students.age, students.age FROM students;");
        assertEquals(List.of(
                new SelectItem(new ColumnRef("students", "age"), null),
                new SelectItem(new ColumnRef("students", "age"), null)),
                qualified.select());
        assertEquals(new TableRef("students", null), qualified.from().get(0));
        assertNull(qualified.where());
    }

    @Test
    void acceptsNewlinesBetweenClauses() {
        Query query = parse("SELECT\nid\nFROM\nstudents\nWHERE\nage\n=\n18");
        assertEquals(query(List.of(item("id")), "students",
                comparison("age", Expression.ComparisonOp.EQ, new Expression.IntLiteral(18))), query);
    }

    @Test
    void comparisonsCoverEveryOperatorAndOperandCombination() {
        assertEquals(Expression.ComparisonOp.EQ, op("age = 1"));
        assertEquals(Expression.ComparisonOp.NE, op("age <> 1"));
        assertEquals(Expression.ComparisonOp.LT, op("age < 1"));
        assertEquals(Expression.ComparisonOp.LE, op("age <= 1"));
        assertEquals(Expression.ComparisonOp.GT, op("age > 1"));
        assertEquals(Expression.ComparisonOp.GE, op("age >= 1"));

        assertEquals(comparison("age", Expression.ComparisonOp.EQ, new Expression.StringLiteral("18")),
                parse("SELECT a FROM t WHERE age = '18'").where());
        assertEquals(new Expression.Comparison(
                new Expression.StringLiteral("Alice"),
                Expression.ComparisonOp.LT,
                new ColumnRef(null, "name")),
                parse("SELECT a FROM t WHERE 'Alice' < name").where());
        assertEquals(new Expression.Comparison(
                new ColumnRef("t", "name"),
                Expression.ComparisonOp.GT,
                new Expression.StringLiteral("Alice")),
                parse("SELECT a FROM t WHERE t.name > 'Alice'").where());
        assertEquals(new Expression.Comparison(
                new Expression.IntLiteral(0),
                Expression.ComparisonOp.NE,
                new Expression.IntLiteral(Integer.MIN_VALUE)),
                parse("SELECT a FROM t WHERE 0 <> -2147483648").where());
        assertEquals(new Expression.StringLiteral("O'Brien"),
                ((Expression.Comparison) parse("SELECT a FROM t WHERE name = 'O''Brien'").where()).right());
        assertEquals("",
                ((Expression.Comparison) parse("SELECT a FROM t WHERE name = ''").where()).right()
                        instanceof Expression.StringLiteral literal ? literal.value() : null);
    }

    @Test
    void precedenceIsAndOverOrAndNotOverAnd() {
        Expression ungrouped = parse("SELECT a FROM t WHERE a = 1 OR b = 2 AND c = 3").where();
        assertEquals(new Expression.Or(
                comparison("a", Expression.ComparisonOp.EQ, new Expression.IntLiteral(1)),
                new Expression.And(
                        comparison("b", Expression.ComparisonOp.EQ, new Expression.IntLiteral(2)),
                        comparison("c", Expression.ComparisonOp.EQ, new Expression.IntLiteral(3)))),
                ungrouped);
        assertTrue(ungrouped instanceof Expression.Or);

        Expression grouped = parse("SELECT a FROM t WHERE (a = 1 OR b = 2) AND c = 3").where();
        assertEquals(new Expression.And(
                new Expression.Or(
                        comparison("a", Expression.ComparisonOp.EQ, new Expression.IntLiteral(1)),
                        comparison("b", Expression.ComparisonOp.EQ, new Expression.IntLiteral(2))),
                comparison("c", Expression.ComparisonOp.EQ, new Expression.IntLiteral(3))),
                grouped);

        Expression negated = parse("SELECT a FROM t WHERE NOT a = 1 AND b = 2").where();
        assertEquals(new Expression.And(
                new Expression.Not(comparison("a", Expression.ComparisonOp.EQ, new Expression.IntLiteral(1))),
                comparison("b", Expression.ComparisonOp.EQ, new Expression.IntLiteral(2))),
                negated);
        assertEquals(new Expression.Or(
                new Expression.Not(comparison("a", Expression.ComparisonOp.EQ, new Expression.IntLiteral(1))),
                comparison("b", Expression.ComparisonOp.EQ, new Expression.IntLiteral(2))),
                parse("SELECT a FROM t WHERE NOT a = 1 OR b = 2").where());
    }

    @Test
    void notAndBooleanOperatorsAssociateToTheLeft() {
        Expression.Comparison a = comparison("a", Expression.ComparisonOp.EQ, new Expression.IntLiteral(1));
        Expression.Comparison b = comparison("b", Expression.ComparisonOp.EQ, new Expression.IntLiteral(2));
        Expression.Comparison c = comparison("c", Expression.ComparisonOp.EQ, new Expression.IntLiteral(3));
        assertEquals(new Expression.And(new Expression.And(a, b), c),
                parse("SELECT a FROM t WHERE a = 1 AND b = 2 AND c = 3").where());
        assertEquals(new Expression.Or(new Expression.Or(a, b), c),
                parse("SELECT a FROM t WHERE a = 1 OR b = 2 OR c = 3").where());
        assertEquals(new Expression.Not(new Expression.Not(new Expression.Not(a))),
                parse("SELECT a FROM t WHERE NOT NOT NOT a = 1").where());
        assertEquals(new Expression.Not(new Expression.Or(
                comparison("age", Expression.ComparisonOp.LT, new Expression.IntLiteral(18)),
                comparison("score", Expression.ComparisonOp.LE, new Expression.IntLiteral(80)))),
                parse("SELECT id FROM students WHERE NOT (age < 18 OR score <= 80)").where());
    }

    @Test
    void unknownNamesAndTypeMismatchesStillParse() {
        Query query = parse("SELECT no_such, missing.column FROM not_a_table WHERE nope = '18'");
        assertEquals(List.of(
                item("no_such"),
                new SelectItem(new ColumnRef("missing", "column"), null)), query.select());
        assertEquals("not_a_table", query.from().get(0).name());
        assertEquals(comparison("nope", Expression.ComparisonOp.EQ, new Expression.StringLiteral("18")),
                query.where());
    }

    @Test
    void namesThatLookLikeClausesParseWhenTheyAreJustNames() {
        Query query = parse("SELECT distinct, order_id FROM join WHERE on_col = 1");
        assertEquals(List.of(item("distinct"), item("order_id")), query.select());
        assertEquals("join", query.from().get(0).name());
        assertEquals(comparison("on_col", Expression.ComparisonOp.EQ, new Expression.IntLiteral(1)),
                query.where());
        assertEquals(new ColumnRef(null, "order"),
                parse("SELECT order FROM t").select().get(0).expression());
        assertEquals(new ColumnRef(null, "as"),
                parse("SELECT as FROM t").select().get(0).expression());
    }

    @Test
    void syntaxErrorsCarryPosition() {
        String missingSelect = "SELECT FROM t";
        SqlParseException missing = reject(missingSelect);
        assertTrue(missing.getMessage().contains("expected column name"));
        assertEquals(missingSelect.indexOf("FROM"), missing.offset());
        assertEquals(1, missing.line());
        assertEquals(8, missing.column());

        String bare = "SELECT a FROM t WHERE age";
        SqlParseException bareColumn = reject(bare);
        assertTrue(bareColumn.getMessage().contains("expected comparison operator"));
        assertTrue(bareColumn.getMessage().contains("end of input"));
        assertEquals(bare.length(), bareColumn.offset());

        String chained = "SELECT a FROM t WHERE age < 20 < 30";
        SqlParseException chain = reject(chained);
        assertTrue(chain.getMessage().contains("chained comparisons"));
        assertEquals(chained.indexOf("< 30"), chain.offset());

        SqlParseException unclosed = reject("SELECT a FROM t WHERE (age = 1");
        assertTrue(unclosed.getMessage().contains("expected ')'"));
        assertTrue(unclosed.getMessage().contains("end of input"));

        SqlParseException commas = reject("SELECT a, FROM t");
        assertTrue(commas.getMessage().contains("expected column name"));
        assertEquals("SELECT a, FROM t".indexOf("FROM"), commas.offset());

        SqlParseException literal = reject("SELECT a FROM t WHERE name = 'abc");
        assertTrue(literal.getMessage().contains("unterminated string literal"));
        assertNull(literal.getCause());

        SqlParseException overflow = reject("SELECT a FROM t WHERE id = 2147483648");
        assertTrue(overflow.getMessage().contains("out of range"));
        assertNull(overflow.getCause());
    }

    @Test
    void rejectsExcludedSyntax() {
        assertRejects("SELECT a FROM t FULL JOIN u ON a = b", "JOIN is not supported");
        assertRejects("SELECT a FROM t, u", "multiple tables are not supported");
        assertRejects("SELECT a FROM t FROM u", "multiple tables are not supported");
        assertRejects("SELECT a AS b FROM t", "aliases are not supported");
        assertEquals("u", parse("SELECT a FROM t AS u").from().get(0).alias());
        assertEquals("u", parse("SELECT a FROM t u").from().get(0).alias());
        assertRejects("SELECT a b FROM t", "column aliases are not supported");
        assertRejects("SELECT t.* FROM t", "table.* is not supported");
        assertRejects("SELECT *, a FROM t", "cannot be mixed");
        assertRejects("SELECT a, * FROM t", "cannot be mixed");
        assertRejects("SELECT DISTINCT a FROM t", "DISTINCT is not supported");
        assertRejects("SELECT count(a) FROM t", "aggregates are not supported");
        assertRejects("SELECT a FROM t ORDER a", "expected BY");
        assertRejects("SELECT a FROM t GROUP BY a", "GROUP BY is not supported");
        assertRejects("SELECT a FROM t HAVING a = 1", "HAVING is not supported");
        assertRejects("SELECT a FROM t LIMIT -1", "LIMIT requires");
        assertRejects("SELECT a FROM t LIMIT 1 OFFSET 1", "OFFSET is not supported");
        assertRejects("SELECT a FROM t FETCH FIRST 1 ROWS ONLY", "FETCH is not supported");
        assertRejects("SELECT a FROM t LIMIT 1 ORDER BY a", "expected end of input");
        assertRejects("SELECT a FROM t LIMIT 1 LIMIT 2", "expected end of input");
        assertRejects("SELECT a FROM t WHERE name LIKE 'a'", "LIKE is not supported");
        assertRejects("SELECT a FROM t WHERE name = NULL", "SQL NULL is not supported");
        assertTrue(parse("SELECT a FROM t WHERE a IS NULL").where() instanceof Expression.IsNull);
        assertRejects("SELECT a FROM t; SELECT b FROM u", "multiple statements are not supported");
        assertRejects("SELECT a FROM t;;", "expected end of input");
        assertRejects("SELECT a FROM t WHERE a = (SELECT b FROM u)", "subqueries are not supported");
        assertRejects("SELECT a FROM t WHERE a+1 = 2", "arithmetic expressions are not supported");
        assertRejects("UPDATE t SET a = 1", "updates are not supported");
        assertRejects("-- hidden\nSELECT a FROM t", "unexpected character '-'");
        assertRejects("SELECT \"id\" FROM t", "unexpected character '\"'");
    }

    @Test
    void aFailedParseDoesNotAffectTheNextCall() {
        assertThrows(SqlParseException.class, () -> parser.parse("SELECT"));
        assertThrows(SqlParseException.class, () -> parser.parse("SELECT a FROM t WHERE name = 'open"));
        Query query = parse("SELECT id FROM students WHERE id = 2");
        assertEquals(query(List.of(item("id")), "students",
                comparison("id", Expression.ComparisonOp.EQ, new Expression.IntLiteral(2))), query);
        Query again = parse("SELECT * FROM other");
        assertEquals(new Expression.Star(), again.select().get(0).expression());
        assertNull(again.where());
    }

    @Test
    void acceptsParenthesesNotAndMixedNestingAtTheDocumentedLimit() {
        Expression expected = comparison("a", Expression.ComparisonOp.EQ, new Expression.IntLiteral(1));
        assertEquals(expected,
                parse("SELECT a FROM t WHERE " + "(".repeat(128) + "a=1" + ")".repeat(128)).where());

        Expression not = parse("SELECT a FROM t WHERE " + "NOT ".repeat(128) + "a=1").where();
        for (int i = 0; i < 128; i++) {
            assertTrue(not instanceof Expression.Not);
            not = ((Expression.Not) not).child();
        }
        assertEquals(expected, not);

        Expression mixed = parse("SELECT a FROM t WHERE " + "NOT (".repeat(64) + "a=1" + ")".repeat(64)).where();
        for (int i = 0; i < 64; i++) {
            assertTrue(mixed instanceof Expression.Not);
            mixed = ((Expression.Not) mixed).child();
        }
        assertEquals(expected, mixed);
    }

    @Test
    void rejectsDeepParenthesesNotAndMixedNestingAsParseErrors() {
        String prefix = "SELECT a FROM t WHERE ";
        for (String predicate : List.of(
                "(".repeat(129) + "a=1" + ")".repeat(129),
                "NOT ".repeat(129) + "a=1",
                "NOT (".repeat(64) + "(a=1)" + ")".repeat(64),
                "(".repeat(10_000) + "a=1" + ")".repeat(10_000),
                "NOT ".repeat(10_000) + "a=1",
                "NOT (".repeat(5_000) + "a=1" + ")".repeat(5_000),
                "(".repeat(10_000) + "a=1")) {
            assertRejects(prefix + predicate, "predicate nesting exceeds limit of 128");
        }
    }

    @Test
    void nestingBudgetIsReleasedBetweenSiblingPredicates() {
        String nested = "(".repeat(128) + "a=1" + ")".repeat(128);
        Expression expected = comparison("a", Expression.ComparisonOp.EQ, new Expression.IntLiteral(1));
        assertEquals(new Expression.And(expected, expected),
                parse("SELECT a FROM t WHERE " + nested + " AND " + nested).where());
        assertEquals(new Expression.Or(expected, expected),
                parse("SELECT a FROM t WHERE " + nested + " OR " + nested).where());
    }

    @Test
    void nestingLimitErrorPointsToTheOffendingTokenAndAllowsParserReuse() {
        String prefix = "SELECT a FROM t WHERE\r\n";
        SqlParseException parens = reject(prefix + "(".repeat(129) + "a=1");
        assertEquals(prefix.length() + 128, parens.offset());
        assertEquals(2, parens.line());
        assertEquals(129, parens.column());

        SqlParseException not = reject(prefix + "NOT ".repeat(129) + "a=1");
        assertEquals(prefix.length() + 128 * 4, not.offset());
        assertEquals(2, not.line());
        assertEquals(513, not.column());

        assertNull(parse("SELECT a FROM t").where());
        assertEquals(comparison("a", Expression.ComparisonOp.EQ, new Expression.IntLiteral(1)),
                parse("SELECT a FROM t WHERE a=1").where());
    }

    private Query parse(String sql) {
        return (Query) parser.parse(sql);
    }

    private SqlParseException reject(String sql) {
        SqlParseException ex = assertThrows(SqlParseException.class, () -> parser.parse(sql));
        assertNull(ex.getCause());
        return ex;
    }

    private void assertRejects(String sql, String detail) {
        SqlParseException ex = reject(sql);
        assertTrue(ex.getMessage().contains(detail), ex.getMessage());
    }

    private static Query query(List<SelectItem> select, String table, Expression where) {
        return new Query(select, List.of(new TableRef(table, null)), List.of(), where);
    }

    private static SelectItem item(String name) {
        return new SelectItem(new ColumnRef(null, name), null);
    }

    private static Expression.Comparison comparison(String column, Expression.ComparisonOp op, Expression right) {
        return new Expression.Comparison(new ColumnRef(null, column), op, right);
    }

    private Expression.ComparisonOp op(String predicate) {
        return ((Expression.Comparison) parse("SELECT a FROM t WHERE " + predicate).where()).op();
    }
}
