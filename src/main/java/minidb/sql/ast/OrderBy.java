package minidb.sql.ast;

/**
 * A sort key; omitted SQL direction means ascending. The key is a {@link ColumnRef}, or an
 * {@link Expression.Aggregate} in an aggregate query.
 */
public record OrderBy(Expression key, boolean ascending) {
    /** The key as a column reference, or null when it is an aggregate call. */
    public ColumnRef column() {
        return key instanceof ColumnRef ref ? ref : null;
    }
}
