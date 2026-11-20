package minidb.storage;

import java.util.Optional;
import minidb.catalog.Table;
import minidb.index.KeyRange;

public interface TableStore {
    RowCursor scan(String tableName);

    /**
     * Rows whose indexed INTEGER column is in range, in the same order as {@link #scan}.
     * Only valid for columns the catalog lists in {@code indexedColumns}.
     */
    default RowCursor indexScan(String tableName, String column, KeyRange range) {
        throw new UnsupportedOperationException("Index scans are not supported by this store");
    }

    /**
     * Builds a B+ tree index on an INTEGER column. Rebuilds an existing index with the same
     * name on that column and returns true; rejects a name used elsewhere or a column
     * already indexed under another name.
     */
    default boolean createIndex(String name, String tableName, String column) {
        throw new UnsupportedOperationException("CREATE INDEX is not supported by this store");
    }

    /** Deletes the index with this name; unknown names are an error. */
    default void dropIndex(String name) {
        throw new UnsupportedOperationException("DROP INDEX is not supported by this store");
    }

    /** Every index file with its name (if readable), table, column, and freshness. Read-only. */
    default java.util.List<IndexListing> listIndexes() {
        throw new UnsupportedOperationException("SHOW INDEXES is not supported by this store");
    }

    /** Create a new table on disk, consuming rows incrementally. Existing tables are rejected. */
    void write(Table table, Iterable<Row> rows);

    /** Collect lazily; reuse only while the table's file metadata and definition match. */
    default TableStatistics statistics(String tableName) {
        throw new UnsupportedOperationException("Statistics are not supported by this store");
    }

    /** Force a complete scan, even when file metadata has not changed. */
    default TableStatistics analyze(String tableName) {
        throw new UnsupportedOperationException("Statistics are not supported by this store");
    }

    /**
     * Return a cached snapshot only if its metadata still matches; never scan data rows.
     * Missing/stale entries or unavailable metadata return empty. Stores without a
     * statistics cache can always return empty.
     */
    default Optional<TableStatistics> cachedStatistics(String tableName) {
        return Optional.empty();
    }
}
