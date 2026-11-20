package minidb.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import minidb.catalog.DirectoryCatalog;

class OllamaQueryTranslatorTest {
    @TempDir Path data;

    @Test
    void translatesUsingALocalFakeOllamaEndpoint() throws Exception {
        Files.writeString(data.resolve("students.schema"), "id INTEGER\nname STRING\nage INTEGER\n");
        Files.writeString(data.resolve("students.csv"), "id,name,age\n1,Alice,20\n");
        String response = "{\"message\":{\"content\":\"{\\\"columns\\\":[\\\"name\\\"],"
                + "\\\"source\\\":{\\\"table\\\":\\\"students\\\",\\\"alias\\\":null},"
                + "\\\"joins\\\":[],\\\"where\\\":{\\\"kind\\\":\\\"comparison\\\","
                + "\\\"left\\\":{\\\"kind\\\":\\\"column\\\",\\\"name\\\":\\\"age\\\"},"
                + "\\\"operator\\\":\\\"GE\\\",\\\"right\\\":{\\\"kind\\\":\\\"literal\\\","
                + "\\\"type\\\":\\\"INTEGER\\\",\\\"value\\\":\\\"18\\\"}},"
                + "\\\"orderBy\\\":[],\\\"limit\\\":null}\"}}";

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/chat", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            String sql = new OllamaQueryTranslator(
                    new DirectoryCatalog(data),
                    java.net.http.HttpClient.newHttpClient(),
                    new ObjectMapper(), "test-model",
                    URI.create("http://localhost:" + server.getAddress().getPort() + "/api/chat"))
                    .translate("show me adult students");
            assertEquals("SELECT name FROM students WHERE age >= 18;", sql);
        } finally {
            server.stop(0);
        }
    }
}
