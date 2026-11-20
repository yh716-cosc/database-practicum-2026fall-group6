package minidb.command;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;

import minidb.catalog.Catalog;

import static minidb.command.QueryRequest.*;

final class QueryRequestValidatorAndDecoder {
    private final ObjectMapper mapper;

    QueryRequestValidatorAndDecoder(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    QueryRequest decode(String response, Catalog catalog) {
        try {
            JsonNode root = readTreeStrict(response);
            JsonNode input = null;
            for (JsonNode block : root.path("content")) {
                if ("tool_use".equals(block.path("type").asText())
                        && "query_request".equals(block.path("name").asText())) {
                    input = block.get("input");
                    break;
                }
            }
            if (input == null || !input.isObject()) {
                throw new QueryRequestException("LLM response did not contain query_request");
            }
            return decodeRequest(input, catalog);
        } catch (QueryRequestException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new QueryRequestException("Invalid LLM response: " + ex.getMessage());
        }

    }

    QueryRequest decodeOllama(String response, Catalog catalog) {
        try {
            JsonNode root = readTreeStrict(response);
            JsonNode message = root.path("message");
            if (!message.isObject() || !message.path("content").isTextual()) {
                throw new QueryRequestException("Ollama response did not contain JSON content");
            }
            String content = message.path("content").textValue();
            try {
                return decodeRequest(readTreeStrict(content), catalog);
            } catch (QueryRequestException ex) {
                throw new QueryRequestException("Invalid Ollama query request: " + ex.getMessage()
                        + " (response: " + content + ")");
            }

        } catch (QueryRequestException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new QueryRequestException("Invalid Ollama response: " + ex.getMessage());
        }
    }

    private JsonNode readTreeStrict(String json) throws java.io.IOException {
        return mapper.reader()
                .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                .readTree(json);
    }

    private QueryRequest decodeRequest(JsonNode input, Catalog catalog) {
        QueryRequest request = parseRequest(input);
        new QueryRequestValidator().validate(request, catalog);
        return request;
    }

    private QueryRequest parseRequest(JsonNode node) {
        List<String> columns = strings(required(node, "columns"));
        TableRequest source = table(required(node, "source"));
        List<JoinRequest> joins = new ArrayList<>();
        for (JsonNode join : required(node, "joins")) {
            joins.add(new JoinRequest(enumValue(join, "type", JoinType.class),
                    table(required(join, "table")), text(join, "leftColumn"),
                    text(join, "rightColumn")));
        }
        JsonNode whereNode = required(node, "where");
        if (whereNode.isObject()
                && ("and".equalsIgnoreCase(whereNode.path("kind").asText())
                        || "or".equalsIgnoreCase(whereNode.path("kind").asText()))
                && (whereNode.path("left").isNull() || whereNode.path("right").isNull())) {
            whereNode = mapper.nullNode();
        }
        Condition where = whereNode.isNull() ? null : condition(whereNode);
        List<SortRequest> orderBy = new ArrayList<>();
        for (JsonNode sort : required(node, "orderBy")) {
            orderBy.add(new SortRequest(text(sort, "column"), booleanValue(sort, "ascending")));
        }
        JsonNode limitNode = required(node, "limit");
        Integer limit = limitNode.isNull() ? null : integerValue(limitNode, "limit");
        if (limit != null && limit == 0) {
            limit = null;
        }
        return new QueryRequest(columns, source, joins, where, orderBy, limit);
    }

    private Condition condition(JsonNode node) {
        return switch (text(node, "kind").toLowerCase()) {
            case "comparison" -> new Comparison(operand(required(node, "left")),
                    enumValue(node, "operator", ComparisonOperator.class),
                    operand(required(node, "right")));
            case "isnull" -> new IsNull(operand(required(node, "operand")),
                    node.path("negated").asBoolean());
            case "and" -> new And(condition(required(node, "left")), condition(required(node, "right")));
            case "or" -> new Or(condition(required(node, "left")), condition(required(node, "right")));
            case "not" -> new Not(condition(required(node, "condition")));
            default -> throw new QueryRequestException("Unsupported condition kind");
        };
    }

    private Operand operand(JsonNode node) {
        return switch (text(node, "kind").toLowerCase()) {
            case "column" -> new Column(text(node, "name"));
            case "literal" -> new Literal(enumValue(node, "type", LiteralType.class),
                    text(node, "value"));
            default -> throw new QueryRequestException("Unsupported operand kind");
        };
    }

    private TableRequest table(JsonNode node) {
        return new TableRequest(text(node, "table"), nullableText(node, "alias"));
    }

    private List<String> strings(JsonNode node) {
        if (!node.isArray()) throw new QueryRequestException("Expected an array");
        List<String> values = new ArrayList<>();
        node.forEach(value -> {
            if (!value.isTextual()) throw new QueryRequestException("Expected text array values");
            values.add(value.textValue());
        });
        return values;
    }

    private JsonNode required(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isMissingNode()) {
            throw new QueryRequestException("Missing model field: " + field);
        }
        return value;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = required(node, field);
        if (!value.isTextual()) throw new QueryRequestException("Field must be text: " + field);
        return value.textValue();
    }

    private String nullableText(JsonNode node, String field) {
        JsonNode value = required(node, field);
        if (value.isNull()) {
            return null;
        }
        String text = text(node, field);
        return text.isBlank() ? null : text;
    }

    private boolean booleanValue(JsonNode node, String field) {
        JsonNode value = required(node, field);
        if (!value.isBoolean()) throw new QueryRequestException("Field must be boolean: " + field);
        return value.booleanValue();
    }

    private int integerValue(JsonNode node, String field) {
        JsonNode value = node;
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new QueryRequestException("Field must be a 32-bit integer: " + field);
        }
        return value.intValue();
    }

    private <T extends Enum<T>> T enumValue(JsonNode node, String field, Class<T> type) {
        try {
            return Enum.valueOf(type, text(node, field).toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new QueryRequestException("Invalid " + field + " value");
        }
    }
}
