package minidb.storage;

import minidb.catalog.Table;

public interface TableStore {
    RowCursor scan(String tableName);

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
}
