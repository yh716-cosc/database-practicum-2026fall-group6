package minidb.sql.ast;

/** SHOW INDEXES: lists every index file in the data directory; changes nothing. */
public record ShowIndexes() implements Statement {
}
