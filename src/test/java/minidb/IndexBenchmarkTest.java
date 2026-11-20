package minidb;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Checks what the benchmark measures; timings themselves are machine-dependent. */
class IndexBenchmarkTest {
    private static final int ROWS = 5000;

    @TempDir Path data;

    @BeforeEach void prepare() {
        IndexBenchmark.generate(data, ROWS);
    }

    @Test void generateWritesTableAndBothIndexes() {
        assertTrue(Files.exists(data.resolve("bench.csv")));
        assertTrue(Files.exists(data.resolve("bench.age.idx")));
        assertTrue(Files.exists(data.resolve("bench.id.idx")));
        assertEquals(ROWS, new Engine(data).execute("SELECT id FROM bench").rows().size());
    }

    @Test void indexedQueryReadsOnlyMatchingRows() {
        IndexBenchmark.Result result = IndexBenchmark.compare(data,
                "SELECT id FROM bench WHERE id >= 100 AND id < 150", 3);
        assertTrue(result.indexUsed());
        assertEquals("index scan on bench.id, keys [100, 149]", result.accessPath());
        assertEquals(50, result.resultRows());
        assertEquals(ROWS, result.rowsReadWithoutIndex());
        assertEquals(50, result.rowsReadWithIndex());
        assertTrue(result.medianMillisWithoutIndex() > 0 && result.medianMillisWithIndex() > 0);
    }

    @Test void indexReadsMatchesOfIndexedColumnThenFilters() {
        IndexBenchmark.Result result = IndexBenchmark.compare(data,
                "SELECT id FROM bench WHERE age = 20 AND score < 500", 1);
        assertEquals("index scan on bench.age, keys [20, 20]", result.accessPath());
        int ageMatches = new Engine(data).execute("SELECT id FROM bench WHERE age = 20").rows().size();
        assertEquals(ageMatches, result.rowsReadWithIndex());
        assertTrue(result.resultRows() < ageMatches);
    }

    @Test void queriesWithoutUsableIndexReadTheSameRows() {
        IndexBenchmark.Result result = IndexBenchmark.compare(data,
                "SELECT id FROM bench WHERE score = 7 OR age = 3", 1);
        assertFalse(result.indexUsed());
        assertEquals("full scan (no usable index)", result.accessPath());
        assertEquals(result.rowsReadWithoutIndex(), result.rowsReadWithIndex());
    }

    @Test void rejectsNonQueriesAndBadRunCounts() {
        assertThrows(IllegalArgumentException.class,
                () -> IndexBenchmark.compare(data, "CREATE INDEX i ON bench (age)", 1));
        assertThrows(IllegalArgumentException.class,
                () -> IndexBenchmark.compare(data, "SELECT id FROM bench", 0));
    }
}
