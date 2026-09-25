package minidb.sql.ast;

import java.util.List;

/**
 * Select-project-join shape. `where` stays null until filters are supported.
 * Grouping, ordering, and subqueries can be added as further fields.
 */
public record Query(
        List<SelectItem> select,
        List<TableRef> from,
        List<Join> joins,
        Expression where) implements Statement {
}
