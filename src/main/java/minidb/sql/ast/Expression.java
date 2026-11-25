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
    record IsNull(Expression operand, boolean negated) implements Expression {}

    /** Aggregate function names, matched case-insensitively in call position. */
    enum AggregateFunction { COUNT, SUM, AVG, MIN, MAX }

    /**
     * An aggregate call. The argument is a {@link ColumnRef}, or {@link Star} for COUNT(*).
     * Allowed in SELECT, HAVING, and ORDER BY; the planner rejects it in WHERE and JOIN ON.
     */
    record Aggregate(AggregateFunction function, Expression argument) implements Expression {}

    /** The sole SELECT item (expanded by the planner) or the argument of COUNT(*). */
    record Star() implements Expression {}
}
