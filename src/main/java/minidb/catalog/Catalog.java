package minidb.catalog;

import java.util.Collection;

public interface Catalog {
    Collection<Table> tables();

    Table getTable(String name);
}
