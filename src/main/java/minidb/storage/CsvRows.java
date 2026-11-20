package minidb.storage;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import minidb.catalog.Column;
import minidb.catalog.Table;
import minidb.catalog.TableDefinition.Format;
import minidb.types.Value;

/** CSV parsing and record conversion shared by scans, index builds, and index lookups. */
final class CsvRows {
    private static final Pattern INTEGER_PATTERN = Pattern.compile("[+-]?[0-9]+");

    private CsvRows() {
    }

    /**
     * Byte tracking needs the UTF-8 charset explicitly: Commons CSV otherwise measures
     * characters with the platform default and miscounts or fails on non-ASCII text.
     */
    static CSVParser parser(Reader reader, boolean trackBytes) throws IOException {
        return CSVParser.builder().setReader(reader).setFormat(CSVFormat.RFC4180)
                .setCharset(StandardCharsets.UTF_8).setTrackBytes(trackBytes).get();
    }

    static List<String> header(Table table) {
        return table.columns().stream().map(Column::name).toList();
    }

    /**
     * Converts one data record to schema types, rejecting wrong field counts and bad integers.
     * In {@link Format#NULLABLE_CSV_V1}, {@code \N} is NULL and a leading backslash is escaped.
     */
    static Row toRow(CSVRecord record, Table table, Format format, String context) {
        return toRow(record.toList(), table, format, context);
    }

    /** Same as {@link #toRow(CSVRecord, Table, Format, String)} for fields split by {@link #parseRecord}. */
    static Row toRow(List<String> fields, Table table, Format format, String context) {
        if (fields.size() != table.columns().size()) {
            throw new StorageException(context + ": expected " + table.columns().size()
                    + " fields, found " + fields.size());
        }
        List<Value> values = new ArrayList<>(fields.size());
        for (int i = 0; i < fields.size(); i++) {
            Column column = table.columns().get(i);
            String text = fields.get(i);
            if (format == Format.NULLABLE_CSV_V1) {
                if (text.equals("\\N")) {
                    values.add(Value.nullValue(column.type()));
                    continue;
                }
                if (text.startsWith("\\")) {
                    if (!text.startsWith("\\\\")) {
                        throw new StorageException(context + ", column " + column.name()
                                + ": invalid nullable CSV escape");
                    }
                    text = text.substring(1);
                }
            }
            Object raw = switch (column.type()) {
                case INTEGER -> {
                    try {
                        if (!INTEGER_PATTERN.matcher(text).matches()) {
                            throw new NumberFormatException("Not a decimal integer");
                        }
                        yield Integer.valueOf(text);
                    } catch (NumberFormatException e) {
                        throw new StorageException(context + ", column " + column.name()
                                + ": expected 32-bit INTEGER, found \"" + text + "\"", e);
                    }
                }
                case STRING -> text;
            };
            values.add(new Value(column.type(), raw));
        }
        return new Row(List.copyOf(values));
    }

    /** Fields of one record and the offset just past its line ending. */
    record ParsedRecord(List<String> fields, int end) {
    }

    /**
     * Splits the UTF-8 record starting at {@code bytes[start]} the way {@link CSVFormat#RFC4180}
     * does, without building a parser: a field is quoted only if it starts with a quote; inside
     * quotes, "" is a literal quote and commas and line breaks are data; after the closing
     * quote only whitespace may precede the comma or line end; elsewhere a quote is literal.
     * The record ends at the first CR, LF, or CRLF outside quotes, or at {@code limit} when
     * {@code atEof}. Returns null if the record may continue past {@code limit}. Splitting
     * bytes is safe because UTF-8 continuation bytes are never ASCII.
     */
    static ParsedRecord parseRecord(byte[] bytes, int start, int limit, boolean atEof) {
        List<String> fields = new ArrayList<>();
        int i = start;
        while (true) {
            if (i < limit && bytes[i] == '"') {
                // Quoted field: decode the runs between doubled quotes.
                StringBuilder text = null;
                int runStart = ++i;
                while (true) {
                    if (i >= limit) {
                        if (atEof) {
                            throw new IllegalArgumentException("end of file inside a quoted field");
                        }
                        return null;
                    }
                    if (bytes[i] != '"') {
                        i++;
                        continue;
                    }
                    if (i + 1 >= limit && !atEof) {
                        return null; // can't tell "" from a closing quote yet
                    }
                    if (i + 1 < limit && bytes[i + 1] == '"') {
                        if (text == null) {
                            text = new StringBuilder();
                        }
                        text.append(new String(bytes, runStart, i + 1 - runStart, StandardCharsets.UTF_8));
                        i += 2;
                        runStart = i;
                        continue;
                    }
                    String run = new String(bytes, runStart, i - runStart, StandardCharsets.UTF_8);
                    fields.add(text == null ? run : text.append(run).toString());
                    i++;
                    break;
                }
                while (i < limit && bytes[i] != ',' && bytes[i] != '\n' && bytes[i] != '\r') {
                    if (!Character.isWhitespace(bytes[i])) {
                        throw new IllegalArgumentException("invalid character after a quoted field");
                    }
                    i++;
                }
            } else {
                int fieldStart = i;
                while (i < limit && bytes[i] != ',' && bytes[i] != '\n' && bytes[i] != '\r') {
                    i++;
                }
                if (i >= limit && !atEof) {
                    return null;
                }
                fields.add(new String(bytes, fieldStart, i - fieldStart, StandardCharsets.UTF_8));
            }
            if (i >= limit) {
                if (!atEof) {
                    return null;
                }
                return new ParsedRecord(fields, i);
            }
            byte b = bytes[i];
            if (b == ',') {
                i++;
                continue;
            }
            // Line end: LF, CR, or CRLF.
            i++;
            if (b == '\r' && i < limit && bytes[i] == '\n') {
                i++;
            }
            return new ParsedRecord(fields, i);
        }
    }
}
