package minidb.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import minidb.catalog.Column;
import minidb.catalog.DirectoryCatalog;
import minidb.catalog.SchemaFiles;
import minidb.catalog.Table;
import minidb.catalog.TableDefinition.Format;
import minidb.types.DataType;
import minidb.types.Value;

class NullableStorageTest {
    @TempDir Path data;
    private static final Table TABLE = new Table("cells", List.of(
            new Column("id", DataType.INTEGER), new Column("text", DataType.STRING)));

    private static Row row(Integer id, String text) {
        return new Row(List.of(new Value(DataType.INTEGER, id), new Value(DataType.STRING, text)));
    }

    private List<Row> read() {
        List<Row> rows = new ArrayList<>();
        try (RowCursor cursor = new DirectoryTableStore(data).scan("cells")) {
            cursor.open();
            Row row;
            while ((row = cursor.next()) != null) rows.add(row);
        }
        return rows;
    }

    @Test void roundTripsTypedNullsAndEveryReservedStringThroughFreshStore() throws Exception {
        List<Row> rows = List.of(row(null, null), row(1, ""), row(2, "NULL"), row(3, "\\N"),
                row(4, "\\\\N"), row(5, "\\"), row(6, "\\other"), row(7, "a\\N"),
                row(Integer.MIN_VALUE, "quoted, \"Zoë\"\n東京"), row(Integer.MAX_VALUE, "00123"));
        new DirectoryTableStore(data).write(TABLE, rows);
        assertTrue(Files.readString(data.resolve("cells.schema"))
                .startsWith(SchemaFiles.NULLABLE_FORMAT_MARKER + "\n"));
        assertEquals(Format.NULLABLE_CSV_V1,
                new DirectoryCatalog(data).getTableDefinition("cells").format());
        assertEquals(TABLE, new DirectoryCatalog(data).getTable("cells"));
        assertEquals(rows, read());
    }

    @Test void legacyStringsKeepTheirMeaningAndLegacyIntegerNullsRemainInvalid() throws Exception {
        Files.writeString(data.resolve("cells.schema"), "id INTEGER\ntext STRING\n");
        Files.writeString(data.resolve("cells.csv"), "id,text\n1,\\N\n2,\\other\n3,NULL\n4,\n");
        assertEquals(List.of(row(1, "\\N"), row(2, "\\other"), row(3, "NULL"), row(4, "")), read());
        assertEquals(Format.LEGACY_CSV, new DirectoryCatalog(data).getTableDefinition("cells").format());
        for (String invalid : List.of("", "\\N")) {
            Files.writeString(data.resolve("cells.csv"), "id,text\n" + invalid + ",value\n");
            assertThrows(StorageException.class, this::read);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"\\bad", "\\n", "\\NULL"})
    void rejectsMalformedVersionedEscapes(String text) throws Exception {
        Files.writeString(data.resolve("cells.schema"),
                SchemaFiles.NULLABLE_FORMAT_MARKER + "\nid INTEGER\ntext STRING\n");
        Files.writeString(data.resolve("cells.csv"), "id,text\n1," + text + "\n");
        StorageException error = assertThrows(StorageException.class, this::read);
        assertTrue(error.getMessage().contains("data record 1, column text"));
    }

    @Test void rejectsUnknownRepeatedAndMisplacedFormatMarkers() throws Exception {
        Files.writeString(data.resolve("cells.csv"), "id,text\n");
        for (String schema : List.of("# minidb-format nullable-csv-v2\nid INTEGER\n",
                SchemaFiles.NULLABLE_FORMAT_MARKER + "\n" + SchemaFiles.NULLABLE_FORMAT_MARKER + "\nid INTEGER\n",
                "id INTEGER\n" + SchemaFiles.NULLABLE_FORMAT_MARKER + "\n")) {
            Files.writeString(data.resolve("cells.schema"), schema);
            assertThrows(StorageException.class, () -> new DirectoryTableStore(data).scan("cells"));
        }
    }

    @Test void typedNullStillMustMatchTheDeclaredType() {
        assertThrows(StorageException.class, () -> new DirectoryTableStore(data).write(TABLE,
                List.of(new Row(List.of(Value.nullValue(DataType.STRING), Value.nullValue(DataType.STRING))))));
    }
}
