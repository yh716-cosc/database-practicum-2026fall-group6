package minidb.sql.ast;

/** DROP INDEX name: deletes the index file created under that name. */
public record DropIndex(String name) implements Statement {
}
