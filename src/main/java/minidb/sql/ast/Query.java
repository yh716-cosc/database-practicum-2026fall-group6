package minidb.sql.ast;

import java.util.List;

/**
 * Select-project-join shape. {@code where} is null when the query has no filter.
 * orderBy is empty without ordering; limit is null without a row limit.
 */
public record Query(
        List<SelectItem> select,
        List<TableRef> from,
        List<Join> joins,
        Expression where,
        List<OrderBy> orderBy,
        Integer limit) implements Statement {
    public Query(List<SelectItem> select, List<TableRef> from, List<Join> joins, Expression where) {
        this(select, from, joins, where, List.of(), null);
    }
}
