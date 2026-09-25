package minidb.sql.ast;

/** A table in FROM. `alias` is null when the query does not rename it. */
public record TableRef(String name, String alias) {
}
