package minidb;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

import minidb.catalog.Catalog;
import minidb.catalog.Column;
import minidb.catalog.DirectoryCatalog;
import minidb.catalog.Table;
import minidb.exec.Executor;
import minidb.exec.Operator;
import minidb.index.KeyRange;
import minidb.plan.LogicalOperator;
import minidb.plan.Optimizer;
import minidb.plan.Planner;
import minidb.sql.SqlParser;
import minidb.sql.ast.Query;
import minidb.sql.ast.Statement;
import minidb.storage.DirectoryTableStore;
import minidb.storage.Row;
import minidb.storage.RowCursor;
import minidb.storage.TableStore;
import minidb.types.DataType;
import minidb.types.Value;

/**
 * Compares a SELECT with and without indexes: same rows, rows read from storage, and time.
 *
 * <pre>
 * java -cp target/minidb-0.1.0.jar minidb.IndexBenchmark --generate target/bench 1000000
 * java -cp target/minidb-0.1.0.jar minidb.IndexBenchmark target/bench "SELECT id FROM bench WHERE age = 20" 5
 * </pre>
 */
public final class IndexBenchmark {
    private IndexBenchmark() {
    }

    /**
     * Rows read counts rows returned by storage cursors, before filtering. Times are medians
     * over all runs and include optimizing, compiling, and executing, but not parsing.
     */
    public record Result(String sql, String accessPath, int resultRows,
            long rowsReadWithoutIndex, long rowsReadWithIndex,
            double medianMillisWithoutIndex, double medianMillisWithIndex) {
        public boolean indexUsed() {
            return accessPath.startsWith("index");
        }

        public double speedup() {
            return medianMillisWithoutIndex / medianMillisWithIndex;
        }
    }

    /**
     * Runs the query once per plan to warm up and check that both return identical rows,
     * then {@code runs} timed times each, alternating which plan goes first.
     */
    public static Result compare(Path dataDir, String sql, int runs) {
        if (runs < 1) {
            throw new IllegalArgumentException("runs must be at least 1");
        }
        Catalog catalog = new DirectoryCatalog(dataDir);
        Statement statement = new SqlParser().parse(sql);
        if (!(statement instanceof Query)) {
            throw new IllegalArgumentException("Only SELECT queries can be benchmarked");
        }
        LogicalOperator logical = new Planner().plan(statement, catalog);
        CountingStore store = new CountingStore(new DirectoryTableStore(dataDir, catalog));
        Executor executor = new Executor(store);
        Optimizer withoutIndex = new Optimizer();
        Optimizer withIndex = new Optimizer(catalog);

        List<Row> expected = run(executor, withoutIndex.optimize(logical));
        List<Row> actual = run(executor, withIndex.optimize(logical));
        if (!expected.equals(actual)) {
            throw new IllegalStateException("Index scan returned different rows than a full scan: " + sql);
        }

        long[] without = new long[runs];
        long[] with = new long[runs];
        long readWithout = 0;
        long readWith = 0;
        for (int i = 0; i < runs; i++) {
            // Alternate the order so neither plan always benefits from a warmer cache.
            for (boolean indexed : i % 2 == 0 ? new boolean[] {false, true} : new boolean[] {true, false}) {
                store.reset();
                long start = System.nanoTime();
                run(executor, (indexed ? withIndex : withoutIndex).optimize(logical));
                long elapsed = System.nanoTime() - start;
                if (indexed) {
                    with[i] = elapsed;
                    readWith = store.rowsRead();
                } else {
                    without[i] = elapsed;
                    readWithout = store.rowsRead();
                }
            }
        }
        return new Result(sql, describe(withIndex.optimize(logical)), expected.size(),
                readWithout, readWith, medianMillis(without), medianMillis(with));
    }

    private static List<Row> run(Executor executor, LogicalOperator plan) {
        Operator root = executor.compile(plan);
        root.open();
        try {
            List<Row> rows = new ArrayList<>();
            Row row;
            while ((row = root.next()) != null) {
                rows.add(row);
            }
            return rows;
        } finally {
            root.close();
        }
    }

    private static double medianMillis(long[] nanos) {
        long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        int middle = sorted.length / 2;
        double median = sorted.length % 2 == 1 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2.0;
        return median / 1_000_000.0;
    }

    /** Index scans in the plan, or a note that every table is fully scanned. */
    static String describe(LogicalOperator plan) {
        List<String> scans = new ArrayList<>();
        collectIndexScans(plan, scans);
        return scans.isEmpty() ? "full scan (no usable index)" : String.join("; ", scans);
    }

    private static void collectIndexScans(LogicalOperator plan, List<String> out) {
        if (plan instanceof LogicalOperator.IndexScan scan) {
            out.add("index scan on " + scan.tableName() + "." + scan.column() + ", keys " + format(scan.range()));
        } else if (plan instanceof LogicalOperator.Filter filter) {
            collectIndexScans(filter.child(), out);
        } else if (plan instanceof LogicalOperator.Project project) {
            collectIndexScans(project.child(), out);
        } else if (plan instanceof LogicalOperator.Sort sort) {
            collectIndexScans(sort.child(), out);
        } else if (plan instanceof LogicalOperator.Limit limit) {
            collectIndexScans(limit.child(), out);
        } else if (plan instanceof LogicalOperator.Join join) {
            collectIndexScans(join.left(), out);
            collectIndexScans(join.right(), out);
        }
    }

    private static String format(KeyRange range) {
        if (range.isEmpty()) {
            return "(empty)";
        }
        String min = range.min() == Integer.MIN_VALUE ? "-inf" : Long.toString(range.min());
        String max = range.max() == Integer.MAX_VALUE ? "+inf" : Long.toString(range.max());
        return "[" + min + ", " + max + "]";
    }

    /**
     * Writes table {@code bench(id, name, age, score)} with deterministic pseudo-random data
     * (age 0-99, score 0-999), then runs CREATE INDEX on age and id.
     */
    public static void generate(Path dataDir, int rows) {
        if (rows < 0) {
            throw new IllegalArgumentException("rows must be non-negative");
        }
        Table table = new Table("bench", List.of(new Column("id", DataType.INTEGER),
                new Column("name", DataType.STRING), new Column("age", DataType.INTEGER),
                new Column("score", DataType.INTEGER)));
        new DirectoryTableStore(dataDir).write(table, () -> new Iterator<>() {
            private final Random random = new Random(4321);
            private int next;

            @Override
            public boolean hasNext() {
                return next < rows;
            }

            @Override
            public Row next() {
                int id = next++;
                return new Row(List.of(new Value(DataType.INTEGER, id),
                        new Value(DataType.STRING, "name " + random.nextInt(10_000)),
                        new Value(DataType.INTEGER, random.nextInt(100)),
                        new Value(DataType.INTEGER, random.nextInt(1000))));
            }
        });
        Engine engine = new Engine(dataDir);
        engine.execute("CREATE INDEX idx_bench_age ON bench (age)");
        engine.execute("CREATE INDEX idx_bench_id ON bench (id)");
    }

    public static void main(String[] args) {
        if (args.length >= 2 && args.length <= 3 && args[0].equals("--generate")) {
            int rows = args.length == 3 ? Integer.parseInt(args[2]) : 1_000_000;
            long start = System.nanoTime();
            generate(Path.of(args[1]), rows);
            System.out.printf("Wrote table bench (%,d rows) with indexes on age and id in %.1f s%n",
                    rows, (System.nanoTime() - start) / 1e9);
            return;
        }
        if (args.length < 2 || args.length > 3) {
            System.err.println("Usage: IndexBenchmark <data-dir> \"<SELECT ...>\" [runs]");
            System.err.println("       IndexBenchmark --generate <data-dir> [rows]");
            System.exit(1);
        }
        Result result = compare(Path.of(args[0]), args[1], args.length == 3 ? Integer.parseInt(args[2]) : 5);
        System.out.println("Query:        " + result.sql());
        System.out.println("Access path:  " + result.accessPath());
        System.out.printf("Result rows:  %,d (identical with and without index)%n", result.resultRows());
        System.out.printf("%-14s%18s%18s%n", "", "without index", "with index");
        System.out.printf("%-14s%,18d%,18d%n", "Rows read:", result.rowsReadWithoutIndex(), result.rowsReadWithIndex());
        System.out.printf("%-14s%15.2f ms%15.2f ms%n", "Median time:",
                result.medianMillisWithoutIndex(), result.medianMillisWithIndex());
        System.out.printf("Speedup:      %.1fx%n", result.speedup());
    }

    /** Counts rows that storage cursors return, for both full and index scans. */
    private static final class CountingStore implements TableStore {
        private final TableStore delegate;
        private long rowsRead;

        CountingStore(TableStore delegate) {
            this.delegate = delegate;
        }

        void reset() {
            rowsRead = 0;
        }

        long rowsRead() {
            return rowsRead;
        }

        @Override
        public RowCursor scan(String tableName) {
            return counting(delegate.scan(tableName));
        }

        @Override
        public RowCursor indexScan(String tableName, String column, KeyRange range) {
            return counting(delegate.indexScan(tableName, column, range));
        }

        @Override
        public void write(Table table, Iterable<Row> rows) {
            delegate.write(table, rows);
        }

        private RowCursor counting(RowCursor cursor) {
            return new RowCursor() {
                @Override
                public void open() {
                    cursor.open();
                }

                @Override
                public Row next() {
                    Row row = cursor.next();
                    if (row != null) {
                        rowsRead++;
                    }
                    return row;
                }

                @Override
                public void close() {
                    cursor.close();
                }
            };
        }
    }
}
