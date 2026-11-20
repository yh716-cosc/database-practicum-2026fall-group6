package minidb.command;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import minidb.catalog.Catalog;

/** Uses a local Ollama model to produce a validated structured query request. */
public final class OllamaQueryTranslator {
    private static final String DEFAULT_MODEL = "qwen3:4b";
    private static final URI DEFAULT_URI = URI.create("http://localhost:11434/api/chat");

    private final Catalog catalog;
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final String model;
    private final URI apiUri;

    public OllamaQueryTranslator(Catalog catalog) {
        this(catalog, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(),
                new ObjectMapper(), System.getenv().getOrDefault("OLLAMA_MODEL", DEFAULT_MODEL),
                URI.create(System.getenv().getOrDefault("OLLAMA_URL", DEFAULT_URI.toString())));
    }

    OllamaQueryTranslator(Catalog catalog, HttpClient client, ObjectMapper mapper,
            String model, URI apiUri) {
        this.catalog = catalog;
        this.client = client;
        this.mapper = mapper;
        this.model = model;
        this.apiUri = apiUri;
    }

    public String translate(String input) {
        QueryRequestException lastFailure = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                String correction = lastFailure == null ? null
                        : "The previous JSON was invalid: " + lastFailure.getMessage()
                                + ". Return one valid JSON object with no duplicate keys. "
                                + "Every condition object must include kind and its required fields.";
                return translateOnce(input, correction);
            } catch (QueryRequestException ex) {
                lastFailure = ex;
                if (attempt < 3) {
                    pauseBeforeRetry(attempt);
                }
            }
        }
        throw lastFailure;
    }

    private String translateOnce(String input, String correction) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", model);
            body.put("stream", false);
            body.set("format", requestSchema());
            body.put("keep_alive", "10m");
            body.putObject("options").put("temperature", 0);
            body.put("think", false);
            ArrayNode messages = body.putArray("messages");
            messages.addObject().put("role", "system").put("content", systemPrompt());
            messages.addObject().put("role", "user").put("content",
                    correction == null ? input : input + "\n\n" + correction);

            HttpRequest request = HttpRequest.newBuilder(apiUri)
                    .timeout(Duration.ofSeconds(90))
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new QueryRequestException("Ollama translation failed with HTTP "
                        + response.statusCode() + ": " + response.body());
            }
            QueryRequest query = new QueryRequestValidatorAndDecoder(mapper)
                    .decodeOllama(response.body(), catalog);
            rejectUnrequestedClauses(input, query);
            return new QueryRequestSqlGenerator().generate(query);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new QueryRequestException("Ollama translation was interrupted");
        } catch (IOException ex) {
            throw new QueryRequestException("Cannot reach Ollama at " + apiUri
                    + ". Start Ollama and verify the model is installed.");
        }
    }

    private void pauseBeforeRetry(int attempt) {
        try {
            Thread.sleep(attempt * 500L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new QueryRequestException("Ollama translation was interrupted");
        }
    }

    private void rejectUnrequestedClauses(String input, QueryRequest query) {
        String normalized = input.toLowerCase(java.util.Locale.ROOT);
        boolean mentionsFilter = normalized.matches(".*\\b(where|filter|whose|adult|over|under|above|below|"
                + "greater|less|equal|at least|at most|between|null)\\b.*");
        if (!mentionsFilter && query.where() != null) {
            throw new QueryRequestException("Model invented a filter not requested by the user");
        }
        boolean mentionsSort = normalized.matches(".*\\b(order|ordered|sort|sorted|highest|lowest|ascending|"
                + "descending)\\b.*");
        if (!mentionsSort && !query.orderBy().isEmpty()) {
            throw new QueryRequestException("Model invented sorting not requested by the user");
        }
    }

    private ObjectNode requestSchema() {
        ObjectNode schema = mapper.createObjectNode().put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.set("columns", listSchema(mapper.createObjectNode().put("type", "string")));
        properties.set("source", tableSchema());
        properties.set("joins", listSchema(joinSchema()));
        properties.set("where", conditionSchema());
        properties.set("orderBy", listSchema(sortSchema()));
        properties.set("limit", mapper.createObjectNode().put("type", "integer"));
        schema.putArray("required").add("columns").add("source").add("joins")
                    .add("where").add("orderBy").add("limit");
        schema.put("additionalProperties", false);
        return schema;
    }

    private ObjectNode tableSchema() {
        ObjectNode schema = mapper.createObjectNode().put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("table").put("type", "string");
        properties.putObject("alias").put("type", "string");
        schema.putArray("required").add("table").add("alias");
        return schema;
    }

    private ObjectNode joinSchema() {
        ObjectNode schema = mapper.createObjectNode().put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("type").put("type", "string").putArray("enum")
                    .add("INNER").add("LEFT").add("RIGHT");
        properties.set("table", tableSchema());
        properties.putObject("leftColumn").put("type", "string");
        properties.putObject("rightColumn").put("type", "string");
        schema.putArray("required").add("type").add("table").add("leftColumn").add("rightColumn");
        schema.put("additionalProperties", false);
        return schema;
    }

    private ObjectNode sortSchema() {
        ObjectNode schema = mapper.createObjectNode().put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("column").put("type", "string");
        properties.putObject("ascending").put("type", "boolean");
        schema.putArray("required").add("column").add("ascending");
        schema.put("additionalProperties", false);
        return schema;
    }

    private ObjectNode conditionSchema() {
        ObjectNode schema = mapper.createObjectNode().put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("kind").put("type", "string")
                    .putArray("enum").add("comparison").add("isNull").add("and").add("or").add("not");
        properties.putObject("left");
        properties.putObject("right");
        properties.putObject("operand");
        properties.putObject("negated").put("type", "boolean");
        properties.putObject("operator").put("type", "string")
                    .putArray("enum").add("EQ").add("NE").add("LT").add("LE").add("GT").add("GE");
        schema.put("additionalProperties", false);
        return schema;
    }

    private ObjectNode listSchema(ObjectNode item) {
        return mapper.createObjectNode().put("type", "array").set("items", item);
    }

    private String systemPrompt() {
        StringBuilder prompt = new StringBuilder("""
                Return only one JSON object matching this exact query request shape:
                {"columns":["name"],"source":{"table":"students","alias":null},
                "joins":[],"where":null,"orderBy":[],"limit":null}.
                This database is read-only. Never invent tables or columns. Supported
                queries have one or two tables, at most one INNER/LEFT/RIGHT equality
                join, optional WHERE predicates, ORDER BY, and a non-negative LIMIT.
                A condition is {"kind":"comparison","left":OPERAND,"operator":"EQ|NE|LT|LE|GT|GE","right":OPERAND},
                {"kind":"isNull","operand":OPERAND,"negated":false},
                {"kind":"and|or","left":CONDITION,"right":CONDITION}, or
                {"kind":"not","condition":CONDITION}. An operand is
                {"kind":"column","name":"table.column"} or
                {"kind":"literal","type":"INTEGER|STRING","value":"..."}.
                Never repeat a JSON key. For example, a valid age filter is:
                {"kind":"comparison","left":{"kind":"column","name":"age"},
                "operator":"GE","right":{"kind":"literal","type":"INTEGER","value":"18"}}.
                Use null for absent where and empty arrays for absent joins/orderBy.
                Do not invent a WHERE condition (including isNull) when the user did
                not request filtering. Use a null alias unless the user explicitly
                requests a table alias. Use an empty orderBy array unless the user
                explicitly requests sorting.
                Available catalog metadata:
                """);
        catalog.tables().forEach(table -> {
            prompt.append("\n- ").append(table.name()).append(": ");
            table.columns().forEach(column -> prompt.append(column.name()).append(" ")
                    .append(column.type()).append(", "));
        });
        return prompt.toString();
    }
}
