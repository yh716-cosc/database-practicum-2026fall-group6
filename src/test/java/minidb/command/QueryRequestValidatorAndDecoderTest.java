package minidb.command;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

import minidb.catalog.DirectoryCatalog;

class QueryRequestValidatorAndDecoderTest {
    @TempDir Path data;

    @Test
    void decodesToolCallAndRejectsInvalidIdentifiers() throws Exception {
        Files.writeString(data.resolve("students.schema"), "id INTEGER\nname STRING\n");
        Files.writeString(data.resolve("students.csv"), "id,name\n1,Alice\n");
        String response = """
                {"content":[{"type":"tool_use","name":"query_request","input":{
                  "columns":["name"],"source":{"table":"students","alias":""},
                  "joins":[],"where":null,"orderBy":[],"limit":0
                }}]}
                """;

        QueryRequest request = new QueryRequestValidatorAndDecoder(new ObjectMapper())
                .decode(response, new DirectoryCatalog(data));
        assertEquals("students", request.source().table());
        assertNull(request.source().alias());
        assertNull(request.limit());
        assertThrows(QueryRequestException.class, () -> new QueryRequestValidator().validate(
                new QueryRequest(java.util.List.of("name; DROP TABLE students"),
                        request.source(), java.util.List.of(), null, java.util.List.of(), null),
                new DirectoryCatalog(data)));
    }
}
