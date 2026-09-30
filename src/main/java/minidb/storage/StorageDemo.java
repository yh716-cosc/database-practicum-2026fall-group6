package minidb.storage;

import java.nio.file.Path;
import java.util.List;

import minidb.catalog.Column;
import minidb.catalog.DirectoryCatalog;
import minidb.catalog.Table;
import minidb.types.DataType;
import minidb.types.Value;

/** Standalone storage demo; does not depend on SQL parsing or execution. */
public final class StorageDemo {
    private StorageDemo() {
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: StorageDemo <output-directory> (students must not exist)");
        }
        Path directory = Path.of(args[0]);
        Table table = new Table("students", List.of(
                new Column("id", DataType.INTEGER), new Column("name", DataType.STRING)));
        TableStore store = new DirectoryTableStore(directory);
        store.write(table, List.of(row(1, "Alice"), row(2, "Smith, Bob"), row(3, "Zoë \"Z\"")));
        System.out.println("Wrote students.csv and students.schema to " + directory);
        System.out.println("Schema: " + new DirectoryCatalog(directory).getTable("students"));
        try (RowCursor cursor = store.scan("students")) {
            cursor.open();
            Row row;
            while ((row = cursor.next()) != null) {
                System.out.println(row.values());
            }
        }
    }

    private static Row row(int id, String name) {
        return new Row(List.of(new Value(DataType.INTEGER, id), new Value(DataType.STRING, name)));
    }
}
