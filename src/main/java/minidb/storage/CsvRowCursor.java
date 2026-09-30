package minidb.storage;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import minidb.catalog.Column;
import minidb.catalog.Table;
import minidb.types.DataType;
import minidb.types.Value;

/** A single-use scan. Keeps only the parser's buffer and the current record in memory. */
final class CsvRowCursor implements RowCursor {
    private static final Pattern INTEGER_PATTERN = Pattern.compile("[+-]?[0-9]+");

    private enum State { NEW, OPEN, EOF, CLOSED }

    private final Path path;
    private final Table table;
    private State state = State.NEW;
    private Reader reader;
    private CSVParser parser;
    private Iterator<CSVRecord> records;
    private long nextRecord = 1;

    CsvRowCursor(Path path, Table table) {
        this.path = path;
        this.table = table;
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
            List<String> expected = table.columns().stream().map(Column::name).toList();
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
            CSVRecord record = records.next();
            String context = path + ", data record " + nextRecord;
            if (record.size() != table.columns().size()) {
                throw new StorageException(context + ": expected " + table.columns().size()
                        + " fields, found " + record.size());
            }
            List<Value> values = new ArrayList<>(record.size());
            for (int i = 0; i < record.size(); i++) {
                Column column = table.columns().get(i);
                String text = record.get(i);
                Object raw = text;
                if (column.type() == DataType.INTEGER) {
                    try {
                        if (!INTEGER_PATTERN.matcher(text).matches()) {
                            throw new NumberFormatException("Not a decimal integer");
                        }
                        raw = Integer.valueOf(text);
                    } catch (NumberFormatException e) {
                        throw new StorageException(context + ", column " + column.name()
                                + ": expected 32-bit INTEGER, found \"" + text + "\"", e);
                    }
                }
                values.add(new Value(column.type(), raw));
            }
            nextRecord++;
            return new Row(List.copyOf(values));
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
