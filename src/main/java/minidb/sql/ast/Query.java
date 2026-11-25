package minidb.sql.ast;

import java.util.List;

/**
 * Select-project-join shape with optional grouping. {@code where} and {@code having} are null
 * when absent; groupBy and orderBy are empty when absent; limit is null without a row limit.
 */
public record Query(
        List<SelectItem> select,
        List<TableRef> from,
        List<Join> joins,
        Expression where,
        List<ColumnRef> groupBy,
        Expression having,
        List<OrderBy> orderBy,
        Integer limit) implements Statement {
    public Query(List<SelectItem> select, List<TableRef> from, List<Join> joins, Expression where) {
        this(select, from, joins, where, List.of(), null, List.of(), null);
    }

    public Query(List<SelectItem> select, List<TableRef> from, List<Join> joins, Expression where,
                 List<OrderBy> orderBy, Integer limit) {
        this(select, from, joins, where, List.of(), null, orderBy, limit);
    }
}
