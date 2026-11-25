package minidb.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import minidb.sql.ast.ColumnRef;
import minidb.sql.ast.CreateIndex;
import minidb.sql.ast.DropIndex;
import minidb.sql.ast.Expression;
import minidb.sql.ast.Query;
import minidb.sql.ast.SelectItem;
import minidb.sql.ast.ShowIndexes;
import minidb.sql.ast.Statement;
import minidb.sql.ast.TableRef;
import minidb.sql.ast.OrderBy;
import minidb.sql.ast.Join;

/**
 * Recursive-descent parser for one SELECT (with optional GROUP BY/HAVING and aggregate calls),
 * CREATE INDEX, DROP INDEX, or SHOW INDEXES statement. Each {@code parse} call
 * uses a fresh tokenizer, so a failure does not affect the next call.
 */
public final class SqlParser implements Parser {
    // Bound recursive NOT/parentheses parsing before exhausting the JVM stack.
    private static final int MAX_PREDICATE_NESTING = 128;

    @Override
    public Statement parse(String sql) {
        Session session = new Session(sql);
        if (session.word("create")) return session.createIndex();
        if (session.word("drop")) return session.dropIndex();
        if (session.word("show")) return session.showIndexes();
        return session.query();
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
            List<Join> joins = new ArrayList<>();
            if (word("inner") || word("join") || word("left") || word("right")) {
                Join.Type type = word("left") ? Join.Type.LEFT : word("right") ? Join.Type.RIGHT : Join.Type.INNER;
                if (!word("join")) advance();
                if (type != Join.Type.INNER && word("outer")) advance();
                if (!word("join")) throw unexpected("JOIN");
                advance();
                TableRef right = fromTable();
                if (!word("on")) throw unexpected("ON");
                advance();
                joins.add(new Join(right, predicate(), type));
            }
            if (word("join") || word("inner") || word("left") || word("right")) {
                throw error("Only two-table JOIN is supported");
            }
            Expression where = null;
            if (current.kind() == Token.Kind.WHERE) {
                advance();
                where = predicate();
            }
            List<ColumnRef> groupBy = new ArrayList<>();
            if (word("group")) {
                advance();
                if (!word("by")) throw unexpected("BY");
                advance();
                do {
                    if (current.kind() == Token.Kind.INTEGER) {
                        throw error("GROUP BY ordinals are not supported; name the column");
                    }
                    if (current.kind() == Token.Kind.IDENTIFIER && peek().kind() == Token.Kind.LPAREN) {
                        throw error("GROUP BY accepts only column references");
                    }
                    groupBy.add(columnRef("GROUP BY column"));
                    if (current.kind() != Token.Kind.COMMA) break;
                    advance();
                } while (true);
            }
            Expression having = null;
            if (word("having")) {
                advance();
                having = predicate();
            }
            List<OrderBy> orderBy = new ArrayList<>();
            if (word("order")) {
                advance();
                if (!word("by")) throw unexpected("BY");
                advance();
                do {
                    Expression key = current.kind() == Token.Kind.IDENTIFIER && peek().kind() == Token.Kind.LPAREN
                            ? aggregateCall() : columnRef("ORDER BY column");
                    boolean ascending = true;
                    if (word("asc") || word("desc")) {
                        ascending = word("asc");
                        advance();
                    }
                    orderBy.add(new OrderBy(key, ascending));
                    if (current.kind() != Token.Kind.COMMA) break;
                    advance();
                } while (true);
            }
            Integer limit = null;
            if (word("limit")) {
                advance();
                if (current.kind() != Token.Kind.INTEGER || current.intValue() < 0) {
                    throw error("LIMIT requires a non-negative 32-bit integer");
                }
                limit = current.intValue();
                advance();
            }
            if (current.kind() == Token.Kind.SEMICOLON) {
                advance();
            }
            if (current.kind() != Token.Kind.EOF) {
                throw unexpected("end of input");
            }
            return new Query(List.copyOf(select), List.of(table), List.copyOf(joins), where,
                    List.copyOf(groupBy), having, List.copyOf(orderBy), limit);
        }

        /** CREATE INDEX name ON table (column) [;] */
        private CreateIndex createIndex() {
            advance();
            if (!word("index")) throw unexpected("INDEX");
            advance();
            if (word("on")) throw error("CREATE INDEX requires an index name");
            String name = expectIdentifier("index name").text();
            if (!word("on")) throw unexpected("ON");
            advance();
            String table = expectIdentifier("table name").text();
            expect(Token.Kind.LPAREN, "'('");
            String column = expectIdentifier("column name").text();
            if (current.kind() == Token.Kind.COMMA) throw error("multi-column indexes are not supported");
            expect(Token.Kind.RPAREN, "')'");
            if (current.kind() == Token.Kind.SEMICOLON) {
                advance();
            }
            if (current.kind() != Token.Kind.EOF) {
                throw unexpected("end of input");
            }
            return new CreateIndex(name, table, column);
        }

        /** DROP INDEX name [;] */
        private DropIndex dropIndex() {
            advance();
            if (!word("index")) throw unexpected("INDEX");
            advance();
            String name = expectIdentifier("index name").text();
            if (current.kind() == Token.Kind.SEMICOLON) {
                advance();
            }
            if (current.kind() != Token.Kind.EOF) {
                throw unexpected("end of input");
            }
            return new DropIndex(name);
        }

        /** SHOW INDEXES [;] (SHOW INDEX is accepted too) */
        private ShowIndexes showIndexes() {
            advance();
            if (!word("indexes") && !word("index")) throw unexpected("INDEXES");
            advance();
            if (current.kind() == Token.Kind.SEMICOLON) {
                advance();
            }
            if (current.kind() != Token.Kind.EOF) {
                throw unexpected("end of input");
            }
            return new ShowIndexes();
        }

        private boolean word(String text) {
            return current.kind() == Token.Kind.IDENTIFIER && current.text().equalsIgnoreCase(text);
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
            if (current.kind() == Token.Kind.IDENTIFIER && peek().kind() == Token.Kind.LPAREN) {
                return new SelectItem(aggregateCall(), null);
            }
            ColumnRef column = columnRef("column name");
            if (current.kind() == Token.Kind.LPAREN) {
                throw error("unsupported function call");
            }
            return new SelectItem(column, null);
        }

        /**
         * name ( * | column ): the caller has seen an identifier followed by '('. Only the five
         * aggregate names are functions; only COUNT accepts *.
         */
        private Expression.Aggregate aggregateCall() {
            Expression.AggregateFunction function = aggregateFunction(current.text());
            if (function == null) {
                throw error("unsupported function " + current.text()
                        + "; supported functions are COUNT, SUM, AVG, MIN, and MAX");
            }
            advance();
            expect(Token.Kind.LPAREN, "'('");
            Expression argument;
            if (current.kind() == Token.Kind.STAR) {
                if (function != Expression.AggregateFunction.COUNT) {
                    throw error(function + "(*) is not supported; only COUNT accepts *");
                }
                advance();
                argument = new Expression.Star();
            } else if (word("distinct")) {
                throw error("DISTINCT aggregates are not supported");
            } else if (current.kind() == Token.Kind.IDENTIFIER && peek().kind() == Token.Kind.LPAREN) {
                throw error("nested function calls are not supported");
            } else if (current.kind() == Token.Kind.IDENTIFIER) {
                argument = columnRef("column name");
            } else if (current.kind() == Token.Kind.RPAREN) {
                throw error(function + " requires one argument");
            } else {
                throw error("aggregate argument must be a column"
                        + (function == Expression.AggregateFunction.COUNT ? " or *" : ""));
            }
            if (current.kind() == Token.Kind.COMMA) {
                throw error(function + " takes exactly one argument");
            }
            expect(Token.Kind.RPAREN, "')'");
            return new Expression.Aggregate(function, argument);
        }

        private boolean continuesAsColumn() {
            Token.Kind kind = peek().kind();
            return kind == Token.Kind.DOT || kind == Token.Kind.COMMA || kind == Token.Kind.FROM;
        }

        private TableRef fromTable() {
            Token name = expectIdentifier("table name");
            String alias = null;
            if (word("as")) {
                advance();
                alias = expectIdentifier("table alias").text();
            } else if (current.kind() == Token.Kind.IDENTIFIER && !word("order") && !word("limit")
                    && clauseMessage(current.text()) == null) {
                alias = current.text();
                advance();
            }
            if (current.kind() == Token.Kind.COMMA || current.kind() == Token.Kind.FROM) {
                throw error("multiple tables are not supported");
            }
            return new TableRef(name.text(), alias);
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
            if (word("is")) {
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
                if (aggregateFunction(current.text()) == null) throw error("unsupported expression");
                return aggregateCall();
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

    /** Diagnostics and clause boundaries; supported keywords here are misplaced, not unsupported. */
    private static String clauseMessage(String text) {
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "as" -> "column aliases are not supported";
            case "full", "cross", "natural" -> "FULL, CROSS, and NATURAL JOIN are not supported";
            case "using" -> "JOIN USING is not supported; use ON";
            case "join", "inner", "left", "right", "outer", "on" ->
                    "unexpected JOIN keyword; expected a two-table INNER/LEFT/RIGHT JOIN with ON";
            case "group" -> "misplaced GROUP BY; expected order WHERE, GROUP BY, HAVING, ORDER BY, LIMIT";
            case "having" -> "misplaced HAVING; expected order WHERE, GROUP BY, HAVING, ORDER BY, LIMIT";
            case "distinct" -> "DISTINCT is not supported";
            case "offset" -> "OFFSET is not supported";
            case "fetch" -> "FETCH is not supported";
            case "like" -> "LIKE is not supported";
            case "is" -> "unexpected IS; expected operand IS [NOT] NULL";
            case "in", "between", "exists", "case", "cast" -> "unsupported expression";
            case "union", "except", "intersect" -> "set operations are not supported";
            case "insert", "update", "delete", "create", "drop", "alter", "into", "values", "set" ->
                    "updates are not supported";
            default -> null;
        };
    }

    private static Expression.AggregateFunction aggregateFunction(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "count" -> Expression.AggregateFunction.COUNT;
            case "sum" -> Expression.AggregateFunction.SUM;
            case "avg" -> Expression.AggregateFunction.AVG;
            case "min" -> Expression.AggregateFunction.MIN;
            case "max" -> Expression.AggregateFunction.MAX;
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
