package minidb.command;

import java.util.List;

public record QueryRequest(
        List<String> columns,
        TableRequest source,
        List<JoinRequest> joins,
        Condition where,
        List<SortRequest> orderBy,
        Integer limit) {
    public QueryRequest {
        columns = List.copyOf(columns);
        joins = List.copyOf(joins);
        orderBy = List.copyOf(orderBy);
    }

    public record TableRequest(String table, String alias) {
    }

    public record JoinRequest(
            JoinType type,
            TableRequest table,
            String leftColumn,
            String rightColumn) {
    }

    public enum JoinType {
        INNER, LEFT, RIGHT
    }

    public record SortRequest(String column, boolean ascending) {
    }

    public sealed interface Condition permits Comparison, IsNull, And, Or, Not {
    }

    public record Comparison(Operand left, ComparisonOperator operator, Operand right)
            implements Condition {
    }

    public record IsNull(Operand operand, boolean negated) implements Condition {
    }

    public record And(Condition left, Condition right) implements Condition {
    }

    public record Or(Condition left, Condition right) implements Condition {
    }

    public record Not(Condition condition) implements Condition {
    }

    public sealed interface Operand permits Column, Literal {
    }

    public record Column(String name) implements Operand {
    }

    public record Literal(LiteralType type, String value) implements Operand {
    }

    public enum LiteralType {
        INTEGER, STRING
    }

    public enum ComparisonOperator {
        EQ, NE, LT, LE, GT, GE
    }
}
