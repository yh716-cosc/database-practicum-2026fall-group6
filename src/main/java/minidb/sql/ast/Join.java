package minidb.sql.ast;

public record Join(TableRef right, Expression condition) {
}
