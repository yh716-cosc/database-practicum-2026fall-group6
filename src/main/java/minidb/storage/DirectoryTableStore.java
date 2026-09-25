package minidb.storage;

import java.nio.file.Path;

/** Reads table files from a directory. The file format is unimplemented. */
public final class DirectoryTableStore implements TableStore {
    @SuppressWarnings("unused")
    private final Path dataDir;

    public DirectoryTableStore(Path dataDir) {
        this.dataDir = dataDir;
    }

    @Override
    public RowCursor scan(String tableName) {
        throw new UnsupportedOperationException("Table scan is not implemented");
    }
}
