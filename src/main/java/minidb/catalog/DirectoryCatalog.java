package minidb.catalog;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

/** Metadata for the files in a data directory. Loading those files is unimplemented. */
public final class DirectoryCatalog implements Catalog {
    @SuppressWarnings("unused")
    private final Path dataDir;

    public DirectoryCatalog(Path dataDir) {
        this.dataDir = dataDir;
    }

    @Override
    public Collection<Table> tables() {
        return List.of();
    }

    @Override
    public Table getTable(String name) {
        throw new UnsupportedOperationException("Catalog lookup is not implemented");
    }
}
