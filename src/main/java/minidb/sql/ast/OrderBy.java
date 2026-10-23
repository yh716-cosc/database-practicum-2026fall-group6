package minidb.sql.ast;

/** A named sort key; omitted SQL direction means ascending. */
public record OrderBy(ColumnRef column, boolean ascending) {}
