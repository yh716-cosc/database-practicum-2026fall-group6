package minidb.command;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import minidb.catalog.Catalog;

import static minidb.command.QueryRequest.*;

public final class QueryRequestValidator {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    public void validate(QueryRequest request, Catalog catalog) {
        if (request == null || request.source() == null) {
            throw new QueryRequestException("Model response did not contain a source table");
        }
        if (request.columns().isEmpty()) {
            throw new QueryRequestException("Model response did not contain selected columns");
        }
        if (request.joins().size() > 1) {
            throw new QueryRequestException("Only one join is supported");
        }
        if (request.limit() != null && request.limit() < 0) {
            throw new QueryRequestException("LIMIT must be non-negative");
        }
        validateTable(request.source(), "source");
        catalog.getTable(request.source().table());
        Set<String> qualifiers = new HashSet<>();
        qualifiers.add(request.source().table());
        if (request.source().alias() != null) {
            validateIdentifier(request.source().alias(), "source alias");
            qualifiers.add(request.source().alias());
        }
        for (String column : request.columns()) validateColumn(column, "selected column");
        for (SortRequest sort : request.orderBy()) validateColumn(sort.column(), "ORDER BY column");
        if (request.where() != null) validateCondition(request.where());
        for (JoinRequest join : request.joins()) {
            if (join == null || join.table() == null || join.type() == null) {
                throw new QueryRequestException("Invalid join in model response");
            }
            validateTable(join.table(), "joined table");
            catalog.getTable(join.table().table());
            if (join.table().alias() != null) {
                validateIdentifier(join.table().alias(), "joined table alias");
                if (!qualifiers.add(join.table().alias())) {
                    throw new QueryRequestException("Duplicate table alias: " + join.table().alias());
                }
            } else if (!qualifiers.add(join.table().table())) {
                throw new QueryRequestException("Duplicate table: " + join.table().table());
            }
            validateColumn(join.leftColumn(), "join column");
            validateColumn(join.rightColumn(), "join column");
        }
    }

    private void validateTable(TableRequest table, String label) {
        validateIdentifier(table.table(), label + " table");
        if (table.alias() != null) validateIdentifier(table.alias(), label + " alias");
    }

    private void validateColumn(String column, String label) {
        if (column == null) throw new QueryRequestException(label + " is missing");
        String[] parts = column.split("\\.", -1);
        if (parts.length > 2 || parts.length == 0) {
            throw new QueryRequestException("Invalid " + label + ": " + column);
        }
        for (String part : parts) validateIdentifier(part, label);
    }

    private void validateCondition(Condition condition) {
        if (condition instanceof Comparison comparison) {
            validateOperand(comparison.left());
            validateOperand(comparison.right());
            if (comparison.operator() == null) throw new QueryRequestException("Comparison operator is missing");
        } else if (condition instanceof IsNull isNull) {
            validateOperand(isNull.operand());
        } else if (condition instanceof And and) {
            validateCondition(and.left());
            validateCondition(and.right());
        } else if (condition instanceof Or or) {
            validateCondition(or.left());
            validateCondition(or.right());
        } else if (condition instanceof Not not) {
            validateCondition(not.condition());
        } else {
            throw new QueryRequestException("Unsupported condition in model response");
        }
    }

    private void validateOperand(Operand operand) {
        if (operand instanceof Column column) {
            validateColumn(column.name(), "condition column");
        } else if (operand instanceof Literal literal) {
            if (literal.type() == null || literal.value() == null) {
                throw new QueryRequestException("Invalid literal in model response");
            }
            if (literal.type() == LiteralType.INTEGER) {
                try {
                    Integer.parseInt(literal.value());
                } catch (NumberFormatException ex) {
                    throw new QueryRequestException("Invalid integer literal: " + literal.value());
                }
            }
        } else {
            throw new QueryRequestException("Invalid operand in model response");
        }
    }

    private void validateIdentifier(String value, String label) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new QueryRequestException("Invalid " + label + ": " + value);
        }
    }
}
