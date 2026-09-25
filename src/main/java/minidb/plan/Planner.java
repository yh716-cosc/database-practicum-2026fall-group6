package minidb.plan;

import minidb.catalog.Catalog;
import minidb.sql.ast.Statement;

public final class Planner {
    public LogicalOperator plan(Statement statement, Catalog catalog) {
        throw new UnsupportedOperationException("Planning is not implemented");
    }
}
