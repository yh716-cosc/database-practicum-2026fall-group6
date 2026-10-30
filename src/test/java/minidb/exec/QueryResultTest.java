package minidb.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import minidb.storage.Row;
import minidb.types.DataType;
import minidb.types.Value;

class QueryResultTest {
    private String print(QueryResult result) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        result.printTo(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        return buffer.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test void printsHeadersAndRawValuesWithVisibleEmptyAndEscapedStrings() {
        QueryResult result = new QueryResult(List.of("id", "name", "name"));
        result.add(new Row(List.of(new Value(DataType.INTEGER, -4),
                new Value(DataType.STRING, ""), new Value(DataType.STRING, "A|B\n\t\\"))));
        assertEquals("id | name | name\n---+------+-----\n-4 | \"\" | A\\|B\\n\\t\\\\\n(1 rows)\n", print(result));
    }

    @Test void printsHeadersForEmptyResult() {
        assertEquals("id\n--\n(0 rows)\n", print(new QueryResult(List.of("id"))));
    }

    @Test void distinguishesNullFromLiteralNullAndQuotedText() {
        QueryResult result = new QueryResult(List.of("text"));
        result.add(new Row(List.of(Value.nullValue(DataType.STRING))));
        result.add(new Row(List.of(new Value(DataType.STRING, "NULL"))));
        result.add(new Row(List.of(new Value(DataType.STRING, "'NULL'"))));
        assertEquals("text\n----\nNULL\n'NULL'\n'''NULL'''\n(3 rows)\n", print(result));
    }
}
