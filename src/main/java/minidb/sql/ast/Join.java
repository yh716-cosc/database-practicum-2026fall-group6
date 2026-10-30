package minidb.sql.ast;

public record Join(TableRef right, Expression condition, Type type) {
    public enum Type { INNER, LEFT, RIGHT }

    public Join(TableRef right, Expression condition) {
        this(right, condition, Type.INNER);
    }
}
