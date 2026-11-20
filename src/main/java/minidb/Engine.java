package minidb;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Locale;

import minidb.catalog.Catalog;
import minidb.catalog.DirectoryCatalog;
import minidb.exec.Executor;
import minidb.exec.Operator;
import minidb.exec.QueryResult;
import minidb.plan.CostBasedOptimizer;
import minidb.plan.LogicalOperator;
import minidb.plan.Planner;
import minidb.plan.Optimizer;
import minidb.sql.Parser;
import minidb.sql.SqlParser;
import minidb.sql.ast.CreateIndex;
import minidb.sql.ast.DropIndex;
import minidb.sql.ast.ShowIndexes;
import minidb.sql.ast.Statement;
import minidb.storage.DirectoryTableStore;
import minidb.storage.IndexListing;
import minidb.storage.Row;
import minidb.storage.TableStore;
import minidb.storage.TableStatistics;
import minidb.types.DataType;
import minidb.types.Value;

/** Parse a query, plan it, then pull rows from the root operator; or run CREATE/DROP/SHOW INDEX(ES). */
public final class Engine {
    private final Catalog catalog;
    private final TableStore store;
    private final Parser parser;
    private final Planner planner;
    private final Executor executor;
    private final CostBasedOptimizer costOptimizer;

    public Engine(Path dataDir) {
        this.catalog = new DirectoryCatalog(dataDir);
        this.store = new DirectoryTableStore(dataDir, catalog);
        this.parser = new SqlParser();
        this.planner = new Planner();
        this.executor = new Executor(store);
        this.costOptimizer = new CostBasedOptimizer(catalog, store);
    }

    public QueryResult execute(String sql) {
        Statement statement = parser.parse(sql);
        if (statement instanceof CreateIndex create) {
            boolean replaced = store.createIndex(create.name(), create.table(), create.column());
            return QueryResult.message((replaced ? "Rebuilt" : "Created") + " index " + create.name()
                    + " on " + create.table() + "(" + create.column() + ")");
        }
        if (statement instanceof DropIndex drop) {
            store.dropIndex(drop.name());
            return QueryResult.message("Dropped index " + drop.name());
        }
        if (statement instanceof ShowIndexes) {
            QueryResult result = new QueryResult(List.of("name", "table", "column", "status"));
            for (IndexListing index : store.listIndexes()) {
                result.add(new Row(List.of(
                        index.name() == null ? Value.nullValue(DataType.STRING) : new Value(DataType.STRING, index.name()),
                        new Value(DataType.STRING, index.table()),
                        new Value(DataType.STRING, index.column()),
                        new Value(DataType.STRING, index.status().name().toLowerCase(Locale.ROOT)))));
            }
            return result;
        }
        LogicalOperator plan = planner.plan(statement, catalog);
        QueryResult result = new QueryResult(columnNames(plan));
        LogicalOperator optimized = new Optimizer(catalog).optimize(plan);
        Operator root = executor.compile(optimized, costOptimizer.chooseJoin(optimized).orElse(null));
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

    /** Collect statistics if absent or stale, sharing the query engine's storage cache. */
    public TableStatistics statistics(String tableName) {
        return store.statistics(tableName);
    }

    /** Force a full validation and refresh, including metadata-preserving external edits. */
    public TableStatistics analyze(String tableName) {
        return store.analyze(tableName);
    }

    /** Inspect current cached statistics without reading CSV data rows. */
    public Optional<TableStatistics> cachedStatistics(String tableName) {
        return store.cachedStatistics(tableName);
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
        if (plan instanceof LogicalOperator.IndexScan scan) {
            return columnNames(new LogicalOperator.Scan(scan.tableName()));
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
