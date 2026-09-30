package minidb.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

import minidb.catalog.Catalog;
import minidb.catalog.Column;
import minidb.catalog.DirectoryCatalog;
import minidb.catalog.SchemaFiles;
import minidb.catalog.Table;
import minidb.types.DataType;
import minidb.types.Value;

/** Streaming CSV storage with explicit schemas. Write new tables between query runs. */
public final class DirectoryTableStore implements TableStore {
    private final Path dataDir;
    private final Catalog catalog;

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
        Table table = catalog.getTable(tableName);
        SchemaFiles.validate(table);
        // Snapshot column order so a caller cannot change the schema during a scan.
        return new CsvRowCursor(dataDir.resolve(tableName + ".csv"),
                new Table(tableName, List.copyOf(table.columns())));
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
                    && (column.type() == DataType.INTEGER ? value.raw() instanceof Integer
                            : value.raw() instanceof String);
            if (!valid) {
                throw new StorageException(context + ", column " + column.name()
                        + ": expected non-null " + column.type() + " value");
            }
            rawValues.add(value.raw());
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
