package minidb;

import java.nio.file.Path;

import minidb.catalog.Catalog;
import minidb.catalog.DirectoryCatalog;
import minidb.exec.Executor;
import minidb.exec.Operator;
import minidb.exec.QueryResult;
import minidb.plan.LogicalOperator;
import minidb.plan.Planner;
import minidb.sql.Parser;
import minidb.sql.SqlParser;
import minidb.sql.ast.Statement;
import minidb.storage.DirectoryTableStore;
import minidb.storage.Row;
import minidb.storage.TableStore;

/** Parse a query, plan it, then pull rows from the root operator. */
public final class Engine {
    private final Catalog catalog;
    private final TableStore store;
    private final Parser parser;
    private final Planner planner;
    private final Executor executor;

    public Engine(Path dataDir) {
        this.catalog = new DirectoryCatalog(dataDir);
        this.store = new DirectoryTableStore(dataDir);
        this.parser = new SqlParser();
        this.planner = new Planner();
        this.executor = new Executor(store);
    }

    public QueryResult execute(String sql) {
        Statement statement = parser.parse(sql);
        LogicalOperator plan = planner.plan(statement, catalog);
        Operator root = executor.compile(plan);
        root.open();
        try {
            QueryResult result = new QueryResult();
            Row row;
            while ((row = root.next()) != null) {
                result.add(row);
            }
            return result;
        } finally {
            root.close();
        }
    }
}
