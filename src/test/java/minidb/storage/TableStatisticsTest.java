package minidb.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.Collection;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import minidb.catalog.Catalog;
import minidb.catalog.Column;
import minidb.catalog.DirectoryCatalog;
import minidb.catalog.Table;
import minidb.catalog.TableDefinition;
import minidb.types.DataType;
import minidb.types.Value;

class TableStatisticsTest {
    @TempDir Path data;
    private static final Table TABLE = new Table("cells", List.of(
            new Column("id", DataType.INTEGER), new Column("text", DataType.STRING)));

    private static Row row(Integer id, String text) {
        return new Row(List.of(new Value(DataType.INTEGER, id), new Value(DataType.STRING, text)));
    }

    private DirectoryTableStore write(List<Row> rows) {
        DirectoryTableStore store = new DirectoryTableStore(data);
        store.write(TABLE, rows);
        return store;
    }

    @Test void countsRowsNullsAndSmallDistinctSetsExactlyAndCachesImmutableResults() throws Exception {
        var store = write(List.of(row(1, ""), row(1, "NULL"), row(2, "\\N"), row(null, null), row(null, "")));
        var stats = store.statistics("cells");
        assertEquals(5, stats.rowCount());
        assertEquals(Files.size(data.resolve("cells.csv")), stats.dataBytes());
        assertEquals(2, stats.columns().get("id").nullCount());
        assertEquals(2, stats.columns().get("id").distinctCount());
        assertEquals(1, stats.columns().get("text").nullCount());
        assertEquals(3, stats.columns().get("text").distinctCount());
        assertTrue(stats.columns().values().stream().allMatch(c -> c.distinctCountExact()));
        assertEquals(List.of("id", "text"), List.copyOf(stats.columns().keySet()));
        assertNotNull(stats.collectedAt());
        assertSame(stats, store.statistics("cells"));
        assertThrows(UnsupportedOperationException.class, () -> stats.columns().clear());
        assertNotSame(stats, store.analyze("cells"));
        assertEquals(5, new DirectoryTableStore(data).statistics("cells").rowCount());
    }

    @Test void emptyAndAllNullTablesHaveZeroDistinctValues() {
        var store = write(List.of());
        var stats = store.statistics("cells");
        assertEquals(0, stats.rowCount());
        assertTrue(stats.columns().values().stream()
                .allMatch(c -> c.nullCount() == 0 && c.distinctCount() == 0 && c.distinctCountExact()));
        var nulls = new Table("nulls", TABLE.columns());
        store.write(nulls, List.of(row(null, null), row(null, null)));
        assertTrue(store.statistics("nulls").columns().values().stream()
                .allMatch(c -> c.nullCount() == 2 && c.distinctCount() == 0 && c.distinctCountExact()));
    }

    @Test void estimatesLargeIntegerAndStringDomainsWithoutCountingDuplicatesOrNulls() {
        int count = 20_000;
        DirectoryTableStore store = new DirectoryTableStore(data);
        store.write(TABLE, () -> IntStream.range(0, count * 2)
                .mapToObj(i -> row(i % count, "value-" + i % count)).iterator());
        var stats = store.statistics("cells");
        assertEquals(count * 2, stats.rowCount());
        for (var column : stats.columns().values()) {
            assertFalse(column.distinctCountExact());
            assertEquals(0, column.nullCount());
            assertTrue(Math.abs(column.distinctCount() - count) < count * 0.15, column.toString());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {256, 257})
    void marksTheExactToEstimatedBoundary(int distinct) {
        var store = write(IntStream.range(0, distinct).mapToObj(i -> row(i, "same")).toList());
        var stats = store.statistics("cells");
        assertEquals(distinct <= 256, stats.columns().get("id").distinctCountExact());
        // With one row per distinct value the known lower/upper bounds coincide here.
        assertEquals(distinct, stats.columns().get("id").distinctCount());
        assertTrue(stats.columns().get("text").distinctCountExact());
        assertEquals(1, stats.columns().get("text").distinctCount());
    }

    @Test void countsCsvRecordsRatherThanPhysicalLinesAndSeparatesHashCodeCollisions() {
        var store = write(List.of(row(1, "Aa"), row(2, "BB"), row(3, "quoted, \"text\"\nnext line")));
        var stats = store.statistics("cells");
        assertEquals(3, stats.rowCount());
        assertEquals(3, stats.columns().get("text").distinctCount());
        assertTrue(stats.columns().get("text").distinctCountExact());
    }

    @Test void refreshesAfterExternalAppendAndSameSizeEditWithChangedTimestamp() throws Exception {
        var store = write(List.of(row(1, "A"), row(1, "A")));
        Path csv = data.resolve("cells.csv");
        Files.setLastModifiedTime(csv, FileTime.fromMillis(10_000));
        var first = store.statistics("cells");
        Files.writeString(csv, "2,B\n", StandardOpenOption.APPEND);
        assertEquals(3, store.statistics("cells").rowCount());
        assertEquals(2, store.statistics("cells").columns().get("id").distinctCount());
        Files.writeString(csv, Files.readString(csv).replace("2,B", "1,A"));
        Files.setLastModifiedTime(csv, FileTime.fromMillis(20_000));
        assertEquals(1, store.statistics("cells").columns().get("id").distinctCount());
        assertEquals(2, first.rowCount()); // previously returned snapshots do not change
    }

    @Test void explicitAnalyzeRefreshesEditsThatPreserveSizeAndTimestamp() throws Exception {
        var store = write(List.of(row(1, "A"), row(1, "A")));
        Path csv = data.resolve("cells.csv");
        var original = store.statistics("cells");
        FileTime modified = Files.getLastModifiedTime(csv);
        Files.writeString(csv, Files.readString(csv).replaceFirst("1,A", "2,B"));
        Files.setLastModifiedTime(csv, modified);
        assertSame(original, store.statistics("cells"));
        var refreshed = store.analyze("cells");
        assertEquals(2, refreshed.columns().get("id").distinctCount());
        assertSame(refreshed, store.statistics("cells"));
    }

    @Test void refreshesForSchemaOnlyEditsEvenWhenSchemaMetadataIsPreserved() throws Exception {
        var store = write(List.of(row(1, "A")));
        store.statistics("cells");
        Path schema = data.resolve("cells.schema");
        FileTime modified = Files.getLastModifiedTime(schema);
        Files.writeString(schema, Files.readString(schema).replace("id INTEGER", "id STRING "));
        Files.setLastModifiedTime(schema, modified);
        assertEquals(DataType.STRING, store.statistics("cells").columns().get("id").type());
    }

    @Test void failuresNeverReturnOrCacheOldOrPartialStatistics() throws Exception {
        var store = write(List.of(row(1, "A")));
        store.statistics("cells");
        Files.writeString(data.resolve("cells.csv"), "id,text\n1,A\nbad,B\n");
        assertThrows(StorageException.class, () -> store.statistics("cells"));
        assertThrows(StorageException.class, () -> store.analyze("cells"));
        Files.writeString(data.resolve("cells.csv"), "id,text\n");
        assertEquals(0, store.statistics("cells").rowCount());
        Files.delete(data.resolve("cells.schema"));
        assertThrows(StorageException.class, () -> store.statistics("cells"));
        assertThrows(StorageException.class, () -> store.statistics("../cells"));
    }

    @Test void rejectsChangesDuringCollectionRatherThanCachingMixedStatistics() throws Exception {
        write(List.of(row(1, "A")));
        DirectoryCatalog delegate = new DirectoryCatalog(data);
        Catalog editingCatalog = new Catalog() {
            public Collection<Table> tables() { return delegate.tables(); }
            public Table getTable(String name) { return delegate.getTable(name); }
            public TableDefinition getTableDefinition(String name) {
                TableDefinition definition = delegate.getTableDefinition(name);
                try {
                    Files.writeString(data.resolve("cells.csv"), "2,B\n", StandardOpenOption.APPEND);
                } catch (java.io.IOException e) {
                    throw new StorageException("Test edit failed", e);
                }
                return definition;
            }
        };
        var store = new DirectoryTableStore(data, editingCatalog);
        StorageException failure = assertThrows(StorageException.class, () -> store.analyze("cells"));
        assertTrue(failure.getMessage().contains("changed during statistics collection"));
        assertEquals(2, new DirectoryTableStore(data).statistics("cells").rowCount());
    }
}
