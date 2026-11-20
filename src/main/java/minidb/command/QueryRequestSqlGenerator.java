package minidb.command;

import static minidb.command.QueryRequest.*;

public final class QueryRequestSqlGenerator {
    public String generate(QueryRequest request) {
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(String.join(", ", request.columns()))
                .append(" FROM ")
                .append(table(request.source()));
        for (JoinRequest join : request.joins()) {
            sql.append(' ').append(join.type()).append(" JOIN ")
                    .append(table(join.table()))
                    .append(" ON ").append(join.leftColumn())
                    .append(" = ").append(join.rightColumn());
        }
        if (request.where() != null) sql.append(" WHERE ").append(condition(request.where()));
        if (!request.orderBy().isEmpty()) {
            sql.append(" ORDER BY ");
            for (int i = 0; i < request.orderBy().size(); i++) {
                if (i > 0) sql.append(", ");
                SortRequest sort = request.orderBy().get(i);
                sql.append(sort.column()).append(sort.ascending() ? " ASC" : " DESC");
            }
        }
        if (request.limit() != null) sql.append(" LIMIT ").append(request.limit());
        return sql.append(';').toString();
    }

    private String table(TableRequest table) {
        return table.table() + (table.alias() == null ? "" : " AS " + table.alias());
    }

    private String condition(Condition value) {
        if (value instanceof Comparison comparison) {
            return operand(comparison.left()) + " " + operator(comparison.operator()) + " "
                    + operand(comparison.right());
        }
        if (value instanceof IsNull isNull) {
            return operand(isNull.operand()) + (isNull.negated() ? " IS NOT NULL" : " IS NULL");
        }
        if (value instanceof And and) return "(" + condition(and.left()) + " AND " + condition(and.right()) + ")";
        if (value instanceof Or or) return "(" + condition(or.left()) + " OR " + condition(or.right()) + ")";
        if (value instanceof Not not) return "NOT (" + condition(not.condition()) + ")";
        throw new QueryRequestException("Unsupported condition");
    }

    private String operand(Operand value) {
        if (value instanceof Column column) return column.name();
        Literal literal = (Literal) value;
        return literal.type() == LiteralType.INTEGER
                ? literal.value()
                : "'" + literal.value().replace("'", "''") + "'";
    }

    private String operator(ComparisonOperator operator) {
        return switch (operator) {
            case EQ -> "=";
            case NE -> "<>";
            case LT -> "<";
            case LE -> "<=";
            case GT -> ">";
            case GE -> ">=";
        };
    }
}
