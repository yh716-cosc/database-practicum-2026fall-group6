package minidb.catalog;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import minidb.storage.StorageException;
import minidb.types.DataType;

/** Simple schema files: one "columnName TYPE" declaration per line. */
public final class SchemaFiles {
    private SchemaFiles() {
    }

    public static void validateName(String name) {
        if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new StorageException("Invalid identifier: " + name);
        }
    }

    public static void validate(Table table) {
        if (table == null) {
            throw new StorageException("Table schema is required");
        }
        validateName(table.name());
        if (table.columns() == null || table.columns().isEmpty()) {
            throw new StorageException("Table " + table.name() + " must have at least one column");
        }
        Set<String> names = new HashSet<>();
        for (Column column : table.columns()) {
            if (column == null || column.type() == null) {
                throw new StorageException("Every column must have a name and type");
            }
            validateName(column.name());
            if (!names.add(column.name())) {
                throw new StorageException("Duplicate column: " + column.name());
            }
        }
    }

    public static Table read(Path path, String tableName) {
        validateName(tableName);
        List<Column> columns = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.strip().split("\\s+");
                if (parts.length != 2) {
                    throw new StorageException(path + ", line " + lineNumber + ": expected columnName TYPE");
                }
                try {
                    columns.add(new Column(parts[0], DataType.valueOf(parts[1])));
                } catch (IllegalArgumentException e) {
                    throw new StorageException(path + ", line " + lineNumber
                            + ": unsupported type " + parts[1] + " (use INTEGER or STRING)", e);
                }
            }
        } catch (IOException e) {
            throw new StorageException("Cannot read schema " + path, e);
        }
        Table table = new Table(tableName, List.copyOf(columns));
        validate(table);
        return table;
    }

    /** Write to a caller-owned staging file; table publication is handled by the store. */
    public static void write(Path path, Table table) throws IOException {
        validate(table);
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            for (Column column : table.columns()) {
                writer.write(column.name() + " " + column.type().name());
                writer.newLine();
            }
        }
    }
}
