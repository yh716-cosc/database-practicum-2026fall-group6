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

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

import minidb.catalog.Catalog;
import minidb.catalog.Column;
import minidb.catalog.DirectoryCatalog;
import minidb.catalog.SchemaFiles;
import minidb.catalog.Table;
import minidb.catalog.TableDefinition;
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

    private TableStatistics collectStatistics(String tableName, boolean force) {
        SchemaFiles.validateName(tableName);
        try {
            FileStamp csvBefore = stamp(dataDir.resolve(tableName + ".csv"));
            FileStamp schemaBefore = stamp(dataDir.resolve(tableName + ".schema"));
            TableDefinition definition = catalog.getTableDefinition(tableName);
            // Snapshot caller-owned column lists, just as scan() does.
            SchemaFiles.validate(definition.table());
            definition = new TableDefinition(new Table(tableName,
                    List.copyOf(definition.table().columns())), definition.format());
            Fingerprint before = new Fingerprint(csvBefore, schemaBefore, definition);
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
            if (!csvBefore.equals(stamp(dataDir.resolve(tableName + ".csv")))
                    || !schemaBefore.equals(stamp(dataDir.resolve(tableName + ".schema")))
                    || !definition.equals(catalog.getTableDefinition(tableName))) {
                throw new StorageException("Table changed during statistics collection: " + tableName
                        + "; finish editing and analyze again");
            }
            TableStatistics result = collector.finish(csvBefore.size());
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
