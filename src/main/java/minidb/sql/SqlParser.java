package minidb.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import minidb.sql.ast.ColumnRef;
import minidb.sql.ast.Expression;
import minidb.sql.ast.Query;
import minidb.sql.ast.SelectItem;
import minidb.sql.ast.Statement;
import minidb.sql.ast.TableRef;

/**
 * Recursive-descent parser for one SELECT statement. Each {@code parse} call
 * uses a fresh tokenizer, so a failure does not affect the next call.
 */
public final class SqlParser implements Parser {
    // Bound recursive NOT/parentheses parsing before exhausting the JVM stack.
    private static final int MAX_PREDICATE_NESTING = 128;

    @Override
    public Statement parse(String sql) {
        return new Session(sql).query();
    }

    private static final class Session {
        private final SqlTokenizer tokenizer;
        private Token current;
        private Token next;
        private int nestingDepth;

        private Session(String sql) {
            tokenizer = new SqlTokenizer(sql);
            current = tokenizer.next();
        }

        private Query query() {
            expect(Token.Kind.SELECT, "SELECT");
            List<SelectItem> select = selectList();
            expect(Token.Kind.FROM, "FROM");
            TableRef table = fromTable();
            Expression where = null;
            if (current.kind() == Token.Kind.WHERE) {
                advance();
                where = predicate();
            }
            if (current.kind() == Token.Kind.SEMICOLON) {
                advance();
            }
            if (current.kind() != Token.Kind.EOF) {
                throw unexpected("end of input");
            }
            return new Query(List.copyOf(select), List.of(table), List.of(), where);
        }

        private List<SelectItem> selectList() {
            if (current.kind() == Token.Kind.STAR) {
                advance();
                if (current.kind() == Token.Kind.COMMA || current.kind() == Token.Kind.STAR) {
                    throw error("SELECT * cannot be mixed with other select items");
                }
                if (current.kind() == Token.Kind.IDENTIFIER) {
                    String clause = clauseMessage(current.text());
                    if (clause != null) {
                        throw error(clause);
                    }
                    throw error("SELECT * cannot be mixed with other select items");
                }
                return List.of(new SelectItem(new Expression.Star(), null));
            }
            List<SelectItem> items = new ArrayList<>();
            items.add(selectColumn());
            while (current.kind() == Token.Kind.COMMA) {
                advance();
                if (current.kind() == Token.Kind.STAR) {
                    throw error("SELECT * cannot be mixed with other select items");
                }
                items.add(selectColumn());
            }
            return items;
        }

        private SelectItem selectColumn() {
            if (current.kind() == Token.Kind.IDENTIFIER
                    && clauseMessage(current.text()) != null
                    && !continuesAsColumn()) {
                throw error(clauseMessage(current.text()));
            }
            ColumnRef column = columnRef("column name");
            if (current.kind() == Token.Kind.LPAREN) {
                throw error("aggregates are not supported");
            }
            return new SelectItem(column, null);
        }

        private boolean continuesAsColumn() {
            Token.Kind kind = peek().kind();
            return kind == Token.Kind.DOT || kind == Token.Kind.COMMA || kind == Token.Kind.FROM;
        }

        private TableRef fromTable() {
            Token name = expectIdentifier("table name");
            if (current.kind() == Token.Kind.IDENTIFIER) {
                String clause = clauseMessage(current.text());
                throw error(clause != null ? clause : "table aliases are not supported");
            }
            if (current.kind() == Token.Kind.COMMA || current.kind() == Token.Kind.FROM) {
                throw error("multiple tables are not supported");
            }
            return new TableRef(name.text(), null);
        }

        private Expression predicate() {
            return orExpr();
        }

        private Expression orExpr() {
            Expression expr = andExpr();
            while (current.kind() == Token.Kind.OR) {
                advance();
                expr = new Expression.Or(expr, andExpr());
            }
            return expr;
        }

        private Expression andExpr() {
            Expression expr = notExpr();
            while (current.kind() == Token.Kind.AND) {
                advance();
                expr = new Expression.And(expr, notExpr());
            }
            return expr;
        }

        private Expression notExpr() {
            if (current.kind() != Token.Kind.NOT && current.kind() != Token.Kind.LPAREN) {
                return comparison();
            }
            if (nestingDepth >= MAX_PREDICATE_NESTING) {
                throw error("predicate nesting exceeds limit of " + MAX_PREDICATE_NESTING);
            }
            nestingDepth++;
            try {
                if (current.kind() == Token.Kind.NOT) {
                    advance();
                    return new Expression.Not(notExpr());
                }
                advance();
                Expression inner = predicate();
                expect(Token.Kind.RPAREN, "')'");
                return inner;
            } finally {
                nestingDepth--;
            }
        }

        private Expression comparison() {
            Expression left = operand();
            if (current.kind() == Token.Kind.IDENTIFIER && current.text().equalsIgnoreCase("is")) {
                advance();
                boolean negated = current.kind() == Token.Kind.NOT;
                if (negated) advance();
                expect(Token.Kind.NULL, "NULL");
                return new Expression.IsNull(left, negated);
            }
            Expression.ComparisonOp op = comparisonOp(current.kind());
            if (op == null) {
                throw unexpected("comparison operator");
            }
            advance();
            Expression right = operand();
            if (comparisonOp(current.kind()) != null) {
                throw error("chained comparisons are not supported");
            }
            return new Expression.Comparison(left, op, right);
        }

        private Expression operand() {
            if (current.kind() == Token.Kind.NULL) {
                throw error("SQL NULL is not supported");
            }
            if (current.kind() == Token.Kind.IDENTIFIER && peek().kind() == Token.Kind.LPAREN) {
                throw error("unsupported expression");
            }
            if (current.kind() == Token.Kind.IDENTIFIER) {
                return columnRef("column name");
            }
            if (current.kind() == Token.Kind.INTEGER) {
                int value = current.intValue();
                advance();
                return new Expression.IntLiteral(value);
            }
            if (current.kind() == Token.Kind.STRING) {
                String value = current.text();
                advance();
                return new Expression.StringLiteral(value);
            }
            if (current.kind() == Token.Kind.LPAREN && peek().kind() == Token.Kind.SELECT) {
                throw error("subqueries are not supported");
            }
            throw unexpected("column, integer, or string");
        }

        private ColumnRef columnRef(String expected) {
            Token first = expectIdentifier(expected);
            if (current.kind() != Token.Kind.DOT) {
                return new ColumnRef(null, first.text());
            }
            advance();
            if (current.kind() == Token.Kind.STAR) {
                throw error("table.* is not supported");
            }
            Token second = expectIdentifier("column name");
            return new ColumnRef(first.text(), second.text());
        }

        private Token expectIdentifier(String expected) {
            if (current.kind() == Token.Kind.NULL) {
                throw error("SQL NULL is not supported");
            }
            if (current.kind() != Token.Kind.IDENTIFIER) {
                throw unexpected(expected);
            }
            Token token = current;
            advance();
            return token;
        }

        private void expect(Token.Kind kind, String expected) {
            if (current.kind() != kind) {
                throw unexpected(expected);
            }
            advance();
        }

        private void advance() {
            if (next != null) {
                current = next;
                next = null;
            } else {
                current = tokenizer.next();
            }
        }

        private Token peek() {
            if (next == null) {
                next = tokenizer.next();
            }
            return next;
        }

        private SqlParseException unexpected(String expected) {
            if (current.kind() == Token.Kind.NULL) {
                return error("SQL NULL is not supported");
            }
            if (current.kind() == Token.Kind.SELECT) {
                return error("multiple statements are not supported");
            }
            if (current.kind() == Token.Kind.INTEGER
                    && (current.text().startsWith("+") || current.text().startsWith("-"))) {
                return error("arithmetic expressions are not supported");
            }
            if (current.kind() == Token.Kind.IDENTIFIER) {
                String clause = clauseMessage(current.text());
                if (clause != null) {
                    return error(clause);
                }
                if (expected.equals("FROM")) {
                    return error("column aliases are not supported");
                }
            }
            if (comparisonOp(current.kind()) != null
                    && (expected.equals("end of input") || expected.equals("')'"))) {
                return error("chained comparisons are not supported");
            }
            return error("expected " + expected + ", found " + describe(current));
        }

        private SqlParseException error(String detail) {
            return new SqlParseException(
                    "line " + current.line() + ", column " + current.column() + ": " + detail,
                    current.offset(), current.line(), current.column());
        }
    }

    private static Expression.ComparisonOp comparisonOp(Token.Kind kind) {
        return switch (kind) {
            case EQ -> Expression.ComparisonOp.EQ;
            case NE -> Expression.ComparisonOp.NE;
            case LT -> Expression.ComparisonOp.LT;
            case LE -> Expression.ComparisonOp.LE;
            case GT -> Expression.ComparisonOp.GT;
            case GE -> Expression.ComparisonOp.GE;
            default -> null;
        };
    }

    /** Message for a clause word, or null when the spelling can be a normal name. */
    private static String clauseMessage(String text) {
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "as" -> "aliases are not supported";
            case "join", "inner", "left", "right", "full", "cross", "outer", "natural", "on", "using" ->
                    "JOIN is not supported";
            case "order" -> "ORDER BY is not supported";
            case "group" -> "GROUP BY is not supported";
            case "having" -> "HAVING is not supported";
            case "distinct" -> "DISTINCT is not supported";
            case "limit", "offset", "fetch" -> "LIMIT is not supported";
            case "like" -> "LIKE is not supported";
            case "is" -> "unexpected IS; expected operand IS [NOT] NULL";
            case "in", "between", "exists", "case", "cast" -> "unsupported expression";
            case "union", "except", "intersect" -> "set operations are not supported";
            case "insert", "update", "delete", "create", "drop", "alter", "into", "values", "set" ->
                    "updates are not supported";
            default -> null;
        };
    }

    private static String describe(Token token) {
        return switch (token.kind()) {
            case EOF -> "end of input";
            case IDENTIFIER -> "identifier '" + token.text() + "'";
            case INTEGER -> "integer '" + token.text() + "'";
            case STRING -> "string '" + token.text() + "'";
            case NULL -> "NULL";
            default -> "'" + token.text() + "'";
        };
    }
}
