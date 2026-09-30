package minidb.storage;

import minidb.catalog.Table;

public interface TableStore {
    RowCursor scan(String tableName);

    /** Create a new table on disk, consuming rows incrementally. Existing tables are rejected. */
    void write(Table table, Iterable<Row> rows);
}
