package minidb;

import java.nio.file.Path;
import java.util.List;

import minidb.catalog.Catalog;
import minidb.catalog.DirectoryCatalog;
import minidb.exec.Executor;
import minidb.exec.Operator;
import minidb.exec.QueryResult;
import minidb.plan.LogicalOperator;
import minidb.plan.Planner;
import minidb.plan.Optimizer;
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
        this.store = new DirectoryTableStore(dataDir, catalog);
        this.parser = new SqlParser();
        this.planner = new Planner();
        this.executor = new Executor(store);
    }

    public QueryResult execute(String sql) {
        Statement statement = parser.parse(sql);
        LogicalOperator plan = planner.plan(statement, catalog);
        QueryResult result = new QueryResult(columnNames(plan));
        Operator root = executor.compile(new Optimizer().optimize(plan));
        root.open();
        try {
            Row row;
            while ((row = root.next()) != null) {
                result.add(row);
            }
            return result;
        } finally {
            root.close();
        }
    }

    /** Output labels follow the logical row layout, including repeated columns. */
    private List<String> columnNames(LogicalOperator plan) {
        if (plan instanceof LogicalOperator.Join join) {
            return java.util.stream.Stream.concat(columnNames(join.left()).stream(),
                    columnNames(join.right()).stream()).toList();
        }
        if (plan instanceof LogicalOperator.Sort sort) return columnNames(sort.child());
        if (plan instanceof LogicalOperator.Limit limit) return columnNames(limit.child());
        if (plan instanceof LogicalOperator.Scan scan) {
            return catalog.getTable(scan.tableName()).columns().stream()
                    .map(column -> column.name()).toList();
        }
        if (plan instanceof LogicalOperator.Filter filter) {
            return columnNames(filter.child());
        }
        if (plan instanceof LogicalOperator.Project project) {
            List<String> input = columnNames(project.child());
            return project.columnIndexes().stream().map(input::get).toList();
        }
        throw new IllegalArgumentException("Unsupported logical plan: " + plan);
    }
}
