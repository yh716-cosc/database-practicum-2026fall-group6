package minidb.storage;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import minidb.catalog.Table;
import minidb.catalog.TableDefinition.Format;
import minidb.index.BPlusTreeReader;
import minidb.index.BPlusTreeWriter;
import minidb.index.IndexSource;
import minidb.types.Value;

/**
 * B+ tree indexes over CSV columns, with record byte offsets as rids. Only CREATE INDEX
 * builds them and only DROP INDEX deletes them; queries use an index only while it still
 * matches its CSV.
 */
public final class CsvIndexes {
    private CsvIndexes() {
    }

    /** One index per column: {@code <table>.<column>.idx} next to the table's CSV. */
    public static Path indexPath(Path dataDir, String tableName, String column) {
        return dataDir.resolve(tableName + "." + column + ".idx");
    }

    /**
     * True if the index exists, is readable, and was built from the current CSV version
     * with the column at the same schema position. Otherwise queries must scan the table.
     */
    public static boolean isFresh(Path csv, Path index, int columnPosition) {
        if (!Files.exists(index)) {
            return false;
        }
        try (BPlusTreeReader reader = BPlusTreeReader.open(index)) {
            return reader.source().equals(currentSource(csv, columnPosition));
        } catch (StorageException unreadable) {
            return false;
        }
    }

    /**
     * The readable index file whose header stores {@code name}, if any. Files are named by
     * table and column, so this opens every index header; only CREATE/DROP INDEX call it.
     */
    public static Optional<Path> findByName(Path dataDir, String name) {
        return indexFiles(dataDir).stream()
                .filter(path -> nameOf(path).filter(name::equals).isPresent()).findFirst();
    }

    /** Every {@code <table>.<column>.idx} file in the data directory, sorted by file name. */
    public static List<Path> indexFiles(Path dataDir) {
        if (!Files.isDirectory(dataDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dataDir)) {
            return files.filter(path -> {
                String file = path.getFileName().toString();
                return file.endsWith(".idx") && !file.startsWith(".") && file.indexOf('.') < file.length() - 4;
            }).sorted().toList();
        } catch (IOException e) {
            throw new StorageException("Cannot list indexes in " + dataDir, e);
        }
    }

    /** The table and column an index file belongs to, from its {@code <table>.<column>.idx} name. */
    public static String[] tableAndColumn(Path index) {
        String file = index.getFileName().toString();
        String stem = file.substring(0, file.length() - ".idx".length());
        int dot = stem.indexOf('.');
        return new String[] {stem.substring(0, dot), stem.substring(dot + 1)};
    }

    /** The name stored in an index file, or empty if it is missing or unreadable. */
    public static Optional<String> nameOf(Path index) {
        if (!Files.isRegularFile(index)) {
            return Optional.empty();
        }
        try (BPlusTreeReader reader = BPlusTreeReader.open(index)) {
            return Optional.of(reader.name());
        } catch (StorageException unreadable) {
            return Optional.empty();
        }
    }

    private static IndexSource currentSource(Path csv, int columnPosition) {
        try {
            Instant modified = Files.getLastModifiedTime(csv).toInstant();
            return new IndexSource(columnPosition, Files.size(csv),
                    modified.getEpochSecond() * 1_000_000_000L + modified.getNano());
        } catch (IOException e) {
            throw new StorageException("Cannot read CSV metadata " + csv, e);
        }
    }

    /**
     * Builds the index, replacing any existing file, and returns whether one existed.
     * Parses and validates every record, so malformed data fails the build. NULL keys are
     * left out: NULL never satisfies a comparison, so index scans must not return those rows.
     */
    static boolean build(Path csv, Path index, Table table, Format format, int columnPosition,
            String name) {
        IndexSource source = currentSource(csv, columnPosition);
        List<BPlusTreeWriter.Entry> entries = new ArrayList<>();
        long record = 1;
        try (Reader reader = Files.newBufferedReader(csv, StandardCharsets.UTF_8);
                CSVParser parser = CsvRows.parser(reader, true)) {
            Iterator<CSVRecord> records = parser.iterator();
            List<String> expected = CsvRows.header(table);
            if (!records.hasNext() || !records.next().toList().equals(expected)) {
                throw new StorageException(csv + ": expected CSV header " + expected);
            }
            while (records.hasNext()) {
                CSVRecord csvRecord = records.next();
                Row row = CsvRows.toRow(csvRecord, table, format, csv + ", data record " + record);
                Value key = row.values().get(columnPosition);
                if (!key.isNull()) {
                    entries.add(new BPlusTreeWriter.Entry((Integer) key.raw(),
                            csvRecord.getBytePosition()));
                }
                record++;
            }
        } catch (IOException | RuntimeException e) {
            throw e instanceof StorageException storage ? storage
                    : new StorageException(csv + ", data record " + record + ": cannot index CSV", e);
        }

        boolean existed = Files.exists(index);
        Path temp = null;
        try {
            temp = Files.createTempFile(index.toAbsolutePath().getParent(), ".minidb-", ".idx.tmp");
            BPlusTreeWriter.write(temp, name, entries, source);
            Files.move(temp, index, StandardCopyOption.REPLACE_EXISTING);
            return existed;
        } catch (IOException | RuntimeException e) {
            StorageException error = e instanceof StorageException storage ? storage
                    : new StorageException("Cannot write index " + index, e);
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException cleanup) {
                    error.addSuppressed(cleanup);
                }
            }
            throw error;
        }
    }
}
