package minidb.sql.ast;

/** CREATE INDEX name ON table (column): builds a B+ tree index over one column. */
public record CreateIndex(String name, String table, String column) implements Statement {
}
