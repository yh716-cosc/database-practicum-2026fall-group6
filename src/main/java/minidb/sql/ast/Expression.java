package minidb.sql.ast;

/** SQL expressions before column binding and type checking. */
public interface Expression {
    record IntLiteral(int value) implements Expression {}

    record StringLiteral(String value) implements Expression {}

    enum ComparisonOp { EQ, NE, LT, LE, GT, GE }

    record Comparison(Expression left, ComparisonOp op, Expression right)
            implements Expression {}

    record And(Expression left, Expression right) implements Expression {}

    record Or(Expression left, Expression right) implements Expression {}

    record Not(Expression child) implements Expression {}

    /** Allowed only as the sole SELECT item; expanded by the planner. */
    record Star() implements Expression {}
}
