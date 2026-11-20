package minidb.sql;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import minidb.sql.ast.CreateIndex;
import minidb.sql.ast.DropIndex;
import minidb.sql.ast.Query;
import minidb.sql.ast.ShowIndexes;

class CreateIndexParserTest {
    private final SqlParser parser = new SqlParser();

    @Test void parsesNamedIndexWithAnyKeywordCaseAndOptionalSemicolon() {
        assertEquals(new CreateIndex("idx_age", "students", "age"),
                parser.parse("CREATE INDEX idx_age ON students (age);"));
        assertEquals(new CreateIndex("Idx", "Students", "Age"),
                parser.parse("create index Idx on Students(Age)"));
    }

    @Test void identifiersNamedLikeKeywordsStillWork() {
        assertEquals(new CreateIndex("limit", "order", "index"),
                parser.parse("CREATE INDEX limit ON order (index)"));
        assertInstanceOf(Query.class, parser.parse("SELECT create FROM t"));
    }

    @Test void rejectsMalformedStatementsWithMessages() {
        for (String sql : List.of(
                "CREATE TABLE t (a)",
                "CREATE INDEX ON students (age)",
                "CREATE INDEX idx students (age)",
                "CREATE INDEX idx ON students age",
                "CREATE INDEX idx ON students (age",
                "CREATE INDEX idx ON students ()",
                "CREATE INDEX idx ON students (age) extra",
                "CREATE INDEX idx ON students (age); SELECT * FROM t",
                "CREATE UNIQUE INDEX idx ON students (age)",
                "CREATE INDEX idx ON students (students.age)")) {
            assertThrows(SqlParseException.class, () -> parser.parse(sql), sql);
        }
        assertTrue(assertThrows(SqlParseException.class,
                () -> parser.parse("CREATE INDEX ON students (age)")).getMessage().contains("requires an index name"));
        assertTrue(assertThrows(SqlParseException.class,
                () -> parser.parse("CREATE INDEX i ON t (a, b)")).getMessage().contains("multi-column"));
    }

    @Test void parsesDropIndex() {
        assertEquals(new DropIndex("idx_age"), parser.parse("DROP INDEX idx_age;"));
        assertEquals(new DropIndex("Idx"), parser.parse("drop index Idx"));
        assertInstanceOf(Query.class, parser.parse("SELECT drop FROM t"));
        for (String sql : List.of("DROP INDEX", "DROP INDEX;", "DROP INDEX a b", "DROP TABLE t",
                "DROP INDEX i; SELECT * FROM t", "DELETE INDEX i", "DROP INDEX t.i")) {
            assertThrows(SqlParseException.class, () -> parser.parse(sql), sql);
        }
    }

    @Test void parsesShowIndexes() {
        assertEquals(new ShowIndexes(), parser.parse("SHOW INDEXES"));
        assertEquals(new ShowIndexes(), parser.parse("show indexes;"));
        assertEquals(new ShowIndexes(), parser.parse("Show Index"));
        assertInstanceOf(Query.class, parser.parse("SELECT show FROM t"));
        for (String sql : List.of("SHOW", "SHOW;", "SHOW TABLES", "SHOW INDEXES x",
                "SHOW INDEXES; SELECT * FROM t", "SHOW INDEXES ON t")) {
            assertThrows(SqlParseException.class, () -> parser.parse(sql), sql);
        }
    }
}
