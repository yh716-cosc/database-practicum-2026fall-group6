package minidb.sql.ast;

/** A column mention. `table` is null when the query does not qualify it. */
public record ColumnRef(String table, String name) implements Expression {
}
