package minidb.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Translates the CLI's supported controlled-English query syntax into SQL.
 * The translator does not inspect the catalog; generated SQL uses the normal
 * parser and planner for schema and type validation.
 */
public final class EnglishCommandTranslator {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    public String translate(String input) {
        if (input == null || input.isBlank()) {
            throw new EnglishCommandException("English command is empty");
        }
        Parser parser = new Parser(input);
        return parser.parse();
    }

    private static final class Parser {
        private final List<Lexeme> lexemes;
        private int index;

        private Parser(String input) {
            lexemes = tokenize(input);
        }

        private String parse() {
            expectWord("show");
            List<String> columns;
            if (acceptWord("all")) {
                columns = List.of("*");
            } else {
                columns = columnList();
            }
            expectWord("from");
            String table = identifier("table name");
            String tableAlias = alias();
            StringBuilder sql = new StringBuilder("SELECT ");
            sql.append(String.join(", ", columns)).append(" FROM ").append(table);
            if (tableAlias != null) {
                sql.append(" AS ").append(tableAlias);
            }
            join(sql);
            if (acceptWord("where")) {
                sql.append(" WHERE ").append(expression());
            }
            orderBy(sql);
            limit(sql);
            if (accept(";")) {
                // A trailing semicolon is accepted for consistency with SQL.
            }
            if (!atEnd()) {
                throw error("unexpected input '" + current().text() + "'");
            }
            return sql.append(';').toString();
        }

        private String alias() {
            if (acceptWord("as")) {
                return identifier("table alias");
            }
            if (!atEnd() && isIdentifier(current())
                    && !isClauseWord(current().text())) {
                return lexemes.get(index++).text();
            }
            return null;
        }

        private void join(StringBuilder sql) {
            String type = "INNER";
            if (acceptWord("left")) {
                type = "LEFT";
                acceptWord("outer");
                expectWord("join");
            } else if (acceptWord("right")) {
                type = "RIGHT";
                acceptWord("outer");
                expectWord("join");
            } else if (acceptWord("inner")) {
                expectWord("join");
            } else if (acceptWord("join")) {
                // JOIN without a modifier is INNER JOIN.
            } else {
                return;
            }
            String table = identifier("joined table name");
            String alias = alias();
            expectWord("on");
            String left = columnReference();
            expectJoinEquality();
            String right = columnReference();
            sql.append(' ').append(type).append(" JOIN ").append(table);
            if (alias != null) {
                sql.append(" AS ").append(alias);
            }
            sql.append(" ON ").append(left).append(" = ").append(right);
        }

        private void expectJoinEquality() {
            if (!(acceptWord("equals") || acceptPhrase("is", "equal", "to"))) {
                throw error("expected 'equals' in JOIN condition");
            }
        }

        private void orderBy(StringBuilder sql) {
            if (acceptWord("order") || acceptWord("sort") || acceptWord("ordered")) {
                expectWord("by");
            } else {
                return;
            }
            sql.append(" ORDER BY ");
            appendOrderKey(sql);
            while (accept(",")) {
                sql.append(", ");
                appendOrderKey(sql);
            }
        }

        private void appendOrderKey(StringBuilder sql) {
            sql.append(columnReference());
            if (acceptWord("asc") || acceptWord("ascending")) {
                sql.append(" ASC");
            } else if (acceptWord("desc") || acceptWord("descending")) {
                sql.append(" DESC");
            }
        }

        private void limit(StringBuilder sql) {
            if (acceptWord("limit")) {
                sql.append(" LIMIT ").append(nonNegativeInteger("LIMIT"));
                return;
            }
            if (acceptWord("return")) {
                expectWord("at");
                expectWord("most");
                sql.append(" LIMIT ").append(nonNegativeInteger("row count"));
                acceptWord("rows");
                return;
            }
            if (acceptWord("limited")) {
                expectWord("to");
                sql.append(" LIMIT ").append(nonNegativeInteger("row count"));
                acceptWord("rows");
            }
        }

        private String nonNegativeInteger(String expected) {
            if (atEnd() || current().quoted() || !current().text().matches("\\d+")) {
                throw error("expected a non-negative integer for " + expected);
            }
            return lexemes.get(index++).text();
        }

        private List<String> columnList() {
            List<String> columns = new ArrayList<>();
            columns.add(columnReference());
            while (accept(",")) {
                columns.add(columnReference());
            }
            return List.copyOf(columns);
        }

        private String columnReference() {
            String first = identifier("column name");
            if (!accept(".")) {
                return first;
            }
            return first + "." + identifier("column name");
        }

        private String expression() {
            return orExpression();
        }

        private String orExpression() {
            String expression = andExpression();
            while (acceptWord("or")) {
                expression = expression + " OR " + andExpression();
            }
            return expression;
        }

        private String andExpression() {
            String expression = notExpression();
            while (acceptWord("and")) {
                expression = expression + " AND " + notExpression();
            }
            return expression;
        }

        private String notExpression() {
            if (acceptWord("not")) {
                return "NOT " + notExpression();
            }
            if (accept("(")) {
                String expression = expression();
                expect(")");
                return "(" + expression + ")";
            }
            return comparison();
        }

        private String comparison() {
            String left = operand();
            if (acceptPhrase("is", "not", "null")) {
                return left + " IS NOT NULL";
            }
            if (acceptPhrase("is", "null")) {
                return left + " IS NULL";
            }
            String operator = comparisonOperator();
            return left + " " + operator + " " + operand();
        }

        private String comparisonOperator() {
            if (acceptWord("equals") || acceptPhrase("is", "equal", "to")) {
                return "=";
            }
            if (acceptPhrase("is", "not", "equal", "to")) {
                return "<>";
            }
            if (acceptPhrase("is", "less", "than")) {
                return "<";
            }
            if (acceptPhrase("is", "at", "most")) {
                return "<=";
            }
            if (acceptPhrase("is", "greater", "than")) {
                return ">";
            }
            if (acceptPhrase("is", "at", "least")) {
                return ">=";
            }
            throw error("expected a comparison such as 'equals' or 'is at least'");
        }

        private String operand() {
            if (atEnd()) {
                throw error("expected a column or value");
            }
            Lexeme lexeme = current();
            if (lexeme.quoted()) {
                index++;
                return quoteSqlString(lexeme.text());
            }
            if (lexeme.text().matches("[+-]?\\d+")) {
                index++;
                return lexeme.text();
            }
            return columnReference();
        }

        private String identifier(String expected) {
            if (atEnd() || !isIdentifier(current())) {
                throw error("expected " + expected);
            }
            return lexemes.get(index++).text();
        }

        private boolean isIdentifier(Lexeme lexeme) {
            return !lexeme.quoted() && IDENTIFIER.matcher(lexeme.text()).matches();
        }

        private static boolean isClauseWord(String word) {
            return switch (word.toLowerCase(Locale.ROOT)) {
                case "where", "join", "inner", "left", "right", "outer", "on",
                        "order", "sort", "ordered", "limit", "return", "limited",
                        "and", "or", "not" -> true;
                default -> false;
            };
        }

        private void expectWord(String word) {
            if (!acceptWord(word)) {
                throw error("expected '" + word + "'");
            }
        }

        private void expect(String text) {
            if (!accept(text)) {
                throw error("expected '" + text + "'");
            }
        }

        private boolean acceptWord(String word) {
            if (!atEnd() && !current().quoted()
                    && current().text().equalsIgnoreCase(word)) {
                index++;
                return true;
            }
            return false;
        }

        private boolean acceptPhrase(String... words) {
            if (index + words.length > lexemes.size()) {
                return false;
            }
            for (int i = 0; i < words.length; i++) {
                Lexeme lexeme = lexemes.get(index + i);
                if (lexeme.quoted() || !lexeme.text().equalsIgnoreCase(words[i])) {
                    return false;
                }
            }
            index += words.length;
            return true;
        }

        private boolean accept(String text) {
            if (!atEnd() && !current().quoted() && current().text().equals(text)) {
                index++;
                return true;
            }
            return false;
        }

        private boolean atEnd() {
            return index == lexemes.size();
        }

        private Lexeme current() {
            return lexemes.get(index);
        }

        private EnglishCommandException error(String message) {
            return new EnglishCommandException(message);
        }
    }

    private record Lexeme(String text, boolean quoted) {
    }

    private static List<Lexeme> tokenize(String input) {
        List<Lexeme> result = new ArrayList<>();
        for (int i = 0; i < input.length();) {
            char character = input.charAt(i);
            if (Character.isWhitespace(character)) {
                i++;
            } else if (character == '\'') {
                StringBuilder value = new StringBuilder();
                i++;
                boolean closed = false;
                while (i < input.length()) {
                    character = input.charAt(i++);
                    if (character == '\'') {
                        if (i < input.length() && input.charAt(i) == '\'') {
                            value.append('\'');
                            i++;
                        } else {
                            closed = true;
                            break;
                        }
                    } else {
                        value.append(character);
                    }
                }
                if (!closed) {
                    throw new EnglishCommandException("unterminated string literal");
                }
                result.add(new Lexeme(value.toString(), true));
            } else if ("(),.;".indexOf(character) >= 0) {
                result.add(new Lexeme(String.valueOf(character), false));
                i++;
            } else if (character == '<' || character == '>' || character == '=') {
                int start = i++;
                if (i < input.length() && input.charAt(i) == '=') {
                    i++;
                } else if (character == '<' && i < input.length() && input.charAt(i) == '>') {
                    i++;
                }
                result.add(new Lexeme(input.substring(start, i), false));
            } else {
                int start = i;
                while (i < input.length()
                        && !Character.isWhitespace(input.charAt(i))
                        && "(),.;<>='".indexOf(input.charAt(i)) < 0) {
                    i++;
                }
                result.add(new Lexeme(input.substring(start, i), false));
            }
        }
        return List.copyOf(result);
    }

    private static String quoteSqlString(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
