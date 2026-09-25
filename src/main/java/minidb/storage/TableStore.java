package minidb.storage;

public interface TableStore {
    RowCursor scan(String tableName);
}
