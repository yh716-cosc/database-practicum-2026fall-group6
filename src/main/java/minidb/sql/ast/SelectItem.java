package minidb.sql.ast;

/** One SELECT entry. `alias` is null when the query does not rename it. */
public record SelectItem(Expression expression, String alias) {
}
