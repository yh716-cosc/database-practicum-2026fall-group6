package minidb.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;

import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.Test;

/** {@link CsvRows#parseRecord} must split records exactly like the Commons CSV parser full scans use. */
class CsvRowsTest {
    /** First record as Commons CSV parses it. */
    private static List<String> commons(String text) throws Exception {
        try (CSVParser parser = CsvRows.parser(new StringReader(text), false)) {
            CSVRecord record = parser.iterator().next();
            return record.toList();
        }
    }

    private static CsvRows.ParsedRecord parse(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return CsvRows.parseRecord(bytes, 0, bytes.length, true);
    }

    @Test void matchesCommonsCsvOnEdgeCases() throws Exception {
        for (String text : List.of("1,plain,20\n", "1,\"Smith, Bob\",20\n", "2,\"multi\nline\",17\r\n",
                "3,\"Zoë 😀 \"\"Z\"\"\",20\n", "5,\"\",20", ",,\n", "a\rb\n", "x,\"\"\"\"\n",
                "\"a\"  ,b\n", "ab\"c,d\n", "\"crlf\r\ninside\",1\r\n", "\n", "only")) {
            assertEquals(commons(text), parse(text).fields(), text);
        }
    }

    @Test void reportsWhereTheRecordEnds() {
        assertEquals(6, parse("1,2,3\nnext\n").end());
        assertEquals(7, parse("1,2,3\r\nnext\n").end());
        assertEquals(6, parse("1,2,3\rnext\n").end());
        assertEquals(5, parse("1,2,3").end());
        assertEquals(8, parse("\"a\nb\",c\nd").end());
    }

    @Test void returnsNullWhenTheRecordMayContinuePastTheLimit() {
        byte[] bytes = "1,\"ab\"\"c\",2\n".getBytes(StandardCharsets.UTF_8);
        for (int limit = 0; limit < bytes.length; limit++) {
            assertNull(CsvRows.parseRecord(bytes, 0, limit, false), "limit " + limit);
        }
        assertEquals(List.of("1", "ab\"c", "2"), CsvRows.parseRecord(bytes, 0, bytes.length, false).fields());
    }

    @Test void rejectsMalformedQuotedFields() {
        assertThrows(IllegalArgumentException.class, () -> parse("\"open,1\n"));
        assertThrows(IllegalArgumentException.class, () -> parse("\"a\"b,1\n"));
    }

    @Test void matchesCommonsCsvOnRandomRecords() throws Exception {
        Random random = new Random(4321);
        String[] pieces = {"a", "é", "😀", " ", ",", "\"", "\n", "\r", "1"};
        for (int n = 0; n < 2000; n++) {
            StringBuilder text = new StringBuilder();
            int fields = 1 + random.nextInt(4);
            for (int f = 0; f < fields; f++) {
                if (f > 0) {
                    text.append(',');
                }
                StringBuilder value = new StringBuilder();
                for (int k = random.nextInt(5); k > 0; k--) {
                    value.append(pieces[random.nextInt(pieces.length)]);
                }
                boolean needsQuotes = value.toString().matches("(?s).*[,\"\r\n].*");
                if (needsQuotes || random.nextBoolean()) {
                    text.append('"').append(value.toString().replace("\"", "\"\"")).append('"');
                } else {
                    text.append(value);
                }
            }
            text.append(random.nextBoolean() ? "\n" : "\r\n").append("next,record\n");
            String csv = text.toString();
            assertEquals(commons(csv), parse(csv).fields(), csv);
        }
    }
}
