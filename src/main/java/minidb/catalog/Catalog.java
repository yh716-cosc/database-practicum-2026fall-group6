package minidb.catalog;

import java.util.Collection;
import java.util.List;

public interface Catalog {
    Collection<Table> tables();

    Table getTable(String name);

    /** Existing/custom catalogs default to the original, non-null CSV encoding. */
    default TableDefinition getTableDefinition(String name) {
        return new TableDefinition(getTable(name), TableDefinition.Format.LEGACY_CSV);
    }

    /** INTEGER columns of the table that have a B+ tree index. */
    default List<String> indexedColumns(String tableName) {
        return List.of();
    }
}
