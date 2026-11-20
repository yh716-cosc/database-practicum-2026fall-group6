package minidb.storage;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import minidb.catalog.Table;
import minidb.catalog.TableDefinition.Format;

/** A single-use scan. Keeps only the parser's buffer and the current record in memory. */
final class CsvRowCursor implements RowCursor {
    private enum State { NEW, OPEN, EOF, CLOSED }

    private final Path path;
    private final Table table;
    private final Format format;
    private State state = State.NEW;
    private Reader reader;
    private CSVParser parser;
    private Iterator<CSVRecord> records;
    private long nextRecord = 1;

    CsvRowCursor(Path path, Table table, Format format) {
        this.path = path;
        this.table = table;
        this.format = format;
    }

    @Override
    public void open() {
        if (state != State.NEW) {
            throw new IllegalStateException("A cursor can only be opened once; request a new scan");
        }
        try {
            reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
            parser = CSVFormat.RFC4180.parse(reader);
            records = parser.iterator();
            List<String> expected = CsvRows.header(table);
            if (!records.hasNext() || !records.next().toList().equals(expected)) {
                throw new StorageException(path + ": expected CSV header " + expected);
            }
            state = State.OPEN;
        } catch (IOException | RuntimeException e) {
            throw fail("Cannot open CSV " + path, e);
        }
    }

    @Override
    public Row next() {
        if (state == State.EOF) {
            return null;
        }
        if (state != State.OPEN) {
            throw new IllegalStateException("Cursor must be open before reading");
        }
        try {
            if (!records.hasNext()) {
                close();
                state = State.EOF;
                return null;
            }
            Row row = CsvRows.toRow(records.next(), table, format, path + ", data record " + nextRecord);
            nextRecord++;
            return row;
        } catch (RuntimeException e) {
            throw fail(path + ", data record " + nextRecord + ": cannot read CSV", e);
        }
    }

    private StorageException fail(String message, Exception cause) {
        StorageException error = cause instanceof StorageException storage ? storage
                : new StorageException(message, cause);
        try {
            close();
        } catch (RuntimeException closeError) {
            error.addSuppressed(closeError);
        }
        return error;
    }

    @Override
    public void close() {
        if (state == State.CLOSED) {
            return;
        }
        state = State.CLOSED;
        try {
            if (parser != null) {
                parser.close();
            } else if (reader != null) {
                reader.close();
            }
        } catch (IOException e) {
            throw new StorageException("Cannot close CSV " + path, e);
        }
    }
}
