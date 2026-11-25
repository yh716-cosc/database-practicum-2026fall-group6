package minidb.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

import minidb.catalog.Catalog;
import minidb.catalog.Column;
import minidb.catalog.DirectoryCatalog;
import minidb.catalog.SchemaFiles;
import minidb.catalog.Table;
import minidb.catalog.TableDefinition;
import minidb.index.BPlusTreeWriter;
import minidb.index.KeyRange;
import minidb.types.DataType;
import minidb.types.Value;

/** Streaming CSV storage with explicit schemas. Write new tables between query runs. */
public final class DirectoryTableStore implements TableStore {
    private final Path dataDir;
    private final Catalog catalog;
    private final Map<String, CachedStatistics> statistics = new HashMap<>();

    public DirectoryTableStore(Path dataDir) {
        this(dataDir, new DirectoryCatalog(dataDir));
    }

    public DirectoryTableStore(Path dataDir, Catalog catalog) {
        this.dataDir = Objects.requireNonNull(dataDir);
        this.catalog = Objects.requireNonNull(catalog);
    }

    @Override
    public RowCursor scan(String tableName) {
        SchemaFiles.validateName(tableName);
        TableDefinition definition = catalog.getTableDefinition(tableName);
        return cursor(tableName, definition);
    }

    private RowCursor cursor(String tableName, TableDefinition definition) {
        Table table = definition.table();
        SchemaFiles.validate(table);
        // Snapshot column order so a caller cannot change the schema during a scan.
        return new CsvRowCursor(dataDir.resolve(tableName + ".csv"),
                new Table(tableName, List.copyOf(table.columns())), definition.format());
    }

    @Override
    public synchronized TableStatistics statistics(String tableName) {
        return collectStatistics(tableName, false);
    }

    @Override
    public synchronized TableStatistics analyze(String tableName) {
        return collectStatistics(tableName, true);
    }

    @Override
    public synchronized Optional<TableStatistics> cachedStatistics(String tableName) {
        SchemaFiles.validateName(tableName);
        CachedStatistics cached = statistics.get(tableName);
        if (cached == null) return Optional.empty();
        try {
            if (cached.fingerprint().equals(fingerprint(tableName))) {
                return Optional.of(cached.value());
            }
        } catch (IOException | RuntimeException e) {
            // An optional planning hint must not force a scan or mask later query errors.
        }
        statistics.remove(tableName);
        return Optional.empty();
    }

    private Fingerprint fingerprint(String tableName) throws IOException {
        FileStamp csv = stamp(dataDir.resolve(tableName + ".csv"));
        FileStamp schema = stamp(dataDir.resolve(tableName + ".schema"));
        TableDefinition definition = catalog.getTableDefinition(tableName);
        SchemaFiles.validate(definition.table());
        // Snapshot caller-owned column lists, just as scan() does.
        definition = new TableDefinition(new Table(tableName,
                List.copyOf(definition.table().columns())), definition.format());
        return new Fingerprint(csv, schema, definition);
    }

    private TableStatistics collectStatistics(String tableName, boolean force) {
        SchemaFiles.validateName(tableName);
        try {
            Fingerprint before = fingerprint(tableName);
            TableDefinition definition = before.definition();
            CachedStatistics cached = statistics.get(tableName);
            if (!force && cached != null && before.equals(cached.fingerprint())) {
                return cached.value();
            }
            statistics.remove(tableName);
            StatisticsCollector collector = new StatisticsCollector(definition.table());
            try (RowCursor cursor = cursor(tableName, definition)) {
                cursor.open();
                Row row;
                while ((row = cursor.next()) != null) collector.add(row);
            }
            if (!before.csv().equals(stamp(dataDir.resolve(tableName + ".csv")))
                    || !before.schema().equals(stamp(dataDir.resolve(tableName + ".schema")))
                    || !definition.equals(catalog.getTableDefinition(tableName))) {
                throw new StorageException("Table changed during statistics collection: " + tableName
                        + "; finish editing and analyze again");
            }
            TableStatistics result = collector.finish(before.csv().size());
            statistics.put(tableName, new CachedStatistics(before, result));
            return result;
        } catch (IOException | RuntimeException e) {
            statistics.remove(tableName);
            if (e instanceof StorageException storage) throw storage;
            throw new StorageException("Cannot collect statistics for " + tableName, e);
        }
    }

    private static FileStamp stamp(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
        if (!attributes.isRegularFile()) throw new StorageException("Not a table file: " + path);
        return new FileStamp(attributes.size(), attributes.lastModifiedTime(),
                attributes.creationTime(), attributes.fileKey());
    }

    private record FileStamp(long size, FileTime modified, FileTime created, Object fileKey) {}
    private record Fingerprint(FileStamp csv, FileStamp schema, TableDefinition definition) {}
    private record CachedStatistics(Fingerprint fingerprint, TableStatistics value) {}

    @Override
    public RowCursor indexScan(String tableName, String column, KeyRange range) {
        SchemaFiles.validateName(tableName);
        Objects.requireNonNull(range, "range");
        TableDefinition definition = catalog.getTableDefinition(tableName);
        Table table = definition.table();
        SchemaFiles.validate(table);
        if (!catalog.indexedColumns(tableName).contains(column)) {
            throw new StorageException("No index on " + tableName + "." + column);
        }
        table = new Table(tableName, List.copyOf(table.columns()));
        int position = table.columns().stream().map(Column::name).toList().indexOf(column);
        return new IndexedRowCursor(dataDir.resolve(tableName + ".csv"),
                CsvIndexes.indexPath(dataDir, tableName, column), table, definition.format(),
                position, range);
    }

    @Override
    public boolean createIndex(String name, String tableName, String column) {
        SchemaFiles.validateName(name);
        if (name.length() > BPlusTreeWriter.MAX_NAME_LENGTH) {
            throw new StorageException("Index name is longer than " + BPlusTreeWriter.MAX_NAME_LENGTH
                    + " characters: " + name);
        }
        SchemaFiles.validateName(tableName);
        TableDefinition definition = catalog.getTableDefinition(tableName);
        Table table = definition.table();
        SchemaFiles.validate(table);
        table = new Table(tableName, List.copyOf(table.columns()));
        int position = table.columns().stream().map(Column::name).toList().indexOf(column);
        if (position < 0) {
            throw new StorageException("Unknown column: " + tableName + "." + column);
        }
        if (table.columns().get(position).type() != DataType.INTEGER) {
            throw new StorageException("Only INTEGER columns can be indexed; " + tableName + "."
                    + column + " is " + table.columns().get(position).type());
        }
        Path index = CsvIndexes.indexPath(dataDir, tableName, column);
        Path sameName = CsvIndexes.findByName(dataDir, name).orElse(null);
        if (sameName != null && !sameName.getFileName().equals(index.getFileName())) {
            String target = sameName.getFileName().toString().replaceFirst("\\.idx$", "");
            throw new StorageException("Index " + name + " already exists on " + target);
        }
        String existing = CsvIndexes.nameOf(index).orElse(name);
        if (!existing.equals(name)) {
            throw new StorageException("Column " + tableName + "." + column + " is already indexed by "
                    + existing + "; DROP INDEX " + existing + " first");
        }
        return CsvIndexes.build(dataDir.resolve(tableName + ".csv"), index, table,
                definition.format(), position, name);
    }

    @Override
    public List<IndexListing> listIndexes() {
        List<IndexListing> listings = new ArrayList<>();
        for (Path index : CsvIndexes.indexFiles(dataDir)) {
            String[] owner = CsvIndexes.tableAndColumn(index);
            String name = CsvIndexes.nameOf(index).orElse(null);
            IndexListing.Status status = name == null ? IndexListing.Status.UNREADABLE
                    : isFresh(owner[0], owner[1], index) ? IndexListing.Status.FRESH
                    : IndexListing.Status.STALE;
            listings.add(new IndexListing(name, owner[0], owner[1], status));
        }
        return List.copyOf(listings);
    }

    /** Same test the catalog uses before planning an index scan; a missing table or column is stale. */
    private boolean isFresh(String tableName, String column, Path index) {
        try {
            List<Column> columns = catalog.getTable(tableName).columns();
            int position = columns.stream().map(Column::name).toList().indexOf(column);
            return position >= 0 && columns.get(position).type() == DataType.INTEGER
                    && CsvIndexes.isFresh(dataDir.resolve(tableName + ".csv"), index, position);
        } catch (RuntimeException missingOrInvalidTable) {
            return false;
        }
    }

    @Override
    public void dropIndex(String name) {
        SchemaFiles.validateName(name);
        Path index = CsvIndexes.findByName(dataDir, name)
                .orElseThrow(() -> new StorageException("Unknown index: " + name));
        try {
            Files.delete(index);
        } catch (IOException e) {
            throw new StorageException("Cannot delete index " + index, e);
        }
    }

    @Override
    public void write(Table table, Iterable<Row> rows) {
        SchemaFiles.validate(table);
        Objects.requireNonNull(rows, "rows");
        table = new Table(table.name(), List.copyOf(table.columns()));
        Path csvPath = dataDir.resolve(table.name() + ".csv");
        Path schemaPath = dataDir.resolve(table.name() + ".schema");
        Path csvTemp = null;
        Path schemaTemp = null;
        boolean csvPublished = false;
        try {
            Files.createDirectories(dataDir);
            if (Files.exists(csvPath, LinkOption.NOFOLLOW_LINKS)
                    || Files.exists(schemaPath, LinkOption.NOFOLLOW_LINKS)) {
                throw new StorageException("Table already exists: " + table.name());
            }
            csvTemp = Files.createTempFile(dataDir, ".minidb-", ".csv.tmp");
            schemaTemp = Files.createTempFile(dataDir, ".minidb-", ".schema.tmp");
            SchemaFiles.write(schemaTemp, table);
            try (var writer = Files.newBufferedWriter(csvTemp, StandardCharsets.UTF_8);
                    var printer = new CSVPrinter(writer, CSVFormat.RFC4180)) {
                printer.printRecord(table.columns().stream().map(Column::name).toList());
                long record = 0;
                for (Row row : rows) {
                    record++;
                    printer.printRecord(validateRow(table, row, record));
                }
            }
            // Publish metadata last so normal discovery only sees completed writes.
            // Two files cannot be committed atomically: this is not crash recovery.
            Files.move(csvTemp, csvPath);
            csvPublished = true;
            Files.move(schemaTemp, schemaPath);
        } catch (IOException | RuntimeException e) {
            StorageException error = e instanceof StorageException storage ? storage
                    : new StorageException("Cannot write table " + table.name(), e);
            if (csvPublished) {
                cleanup(csvPath, error);
            }
            cleanup(csvTemp, error);
            cleanup(schemaTemp, error);
            throw error;
        }
    }

    private static List<Object> validateRow(Table table, Row row, long record) {
        String context = table.name() + ", data record " + record;
        if (row == null || row.values() == null || row.values().size() != table.columns().size()) {
            throw new StorageException(context + ": expected " + table.columns().size() + " values");
        }
        List<Object> rawValues = new ArrayList<>(table.columns().size());
        for (int i = 0; i < table.columns().size(); i++) {
            Column column = table.columns().get(i);
            Value value = row.values().get(i);
            boolean valid = value != null && value.type() == column.type()
                    && (value.isNull() || switch (column.type()) {
                        case INTEGER -> value.raw() instanceof Integer;
                        case STRING -> value.raw() instanceof String;
                        case BIGINT, DECIMAL -> false;
                    });
            if (!valid) {
                throw new StorageException(context + ", column " + column.name()
                        + ": expected " + column.type() + " value or typed NULL");
            }
            Object raw = value.raw();
            // Escape the escape prefix as well as the NULL token; CSV quoting is separate.
            rawValues.add(value.isNull() ? "\\N"
                    : raw instanceof String text && text.startsWith("\\") ? "\\" + text : raw);
        }
        return rawValues;
    }

    private static void cleanup(Path path, StorageException error) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                error.addSuppressed(e);
            }
        }
    }
}
