package minidb.catalog;

import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import minidb.storage.StorageException;

/** Metadata from paired <table>.schema and <table>.csv files. Names are case-sensitive. */
public final class DirectoryCatalog implements Catalog {
    private final Path dataDir;

    public DirectoryCatalog(Path dataDir) {
        this.dataDir = dataDir;
    }

    @Override
    public Collection<Table> tables() {
        try (var paths = Files.list(dataDir)) {
            List<Table> tables = new ArrayList<>();
            for (Path path : paths.filter(p -> p.getFileName().toString().endsWith(".schema"))
                    .sorted().toList()) {
                String filename = path.getFileName().toString();
                tables.add(getTable(filename.substring(0, filename.length() - ".schema".length())));
            }
            return List.copyOf(tables);
        } catch (IOException e) {
            throw new StorageException("Cannot list tables in " + dataDir, e);
        }
    }

    @Override
    public Table getTable(String name) {
        SchemaFiles.validateName(name);
        if (!Files.isRegularFile(dataDir.resolve(name + ".csv"))) {
            throw new StorageException("Missing table data: " + dataDir.resolve(name + ".csv"));
        }
        return SchemaFiles.read(dataDir.resolve(name + ".schema"), name);
    }
}
