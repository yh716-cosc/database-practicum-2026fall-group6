package minidb.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import static minidb.command.QueryRequest.*;

class QueryRequestSqlGeneratorTest {
    @Test
    void generatesValidatedQueryShape() {
        QueryRequest request = new QueryRequest(
                List.of("s.name", "e.course"),
                new TableRequest("students", "s"),
                List.of(new JoinRequest(JoinType.INNER,
                        new TableRequest("enrollments", "e"),
                        "s.id", "e.student_id")),
                new And(
                        new Comparison(new Column("s.age"), ComparisonOperator.GE,
                                new Literal(LiteralType.INTEGER, "18")),
                        new Comparison(new Column("e.course"), ComparisonOperator.NE,
                                new Literal(LiteralType.STRING, "O'Brien"))),
                List.of(new SortRequest("e.course", false)),
                10);

        assertEquals("SELECT s.name, e.course FROM students AS s INNER JOIN enrollments AS e "
                        + "ON s.id = e.student_id WHERE (s.age >= 18 AND e.course <> 'O''Brien') "
                        + "ORDER BY e.course DESC LIMIT 10;",
                new QueryRequestSqlGenerator().generate(request));
    }
}
