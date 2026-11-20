package minidb.index;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.LongStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import minidb.storage.StorageException;

class BPlusTreeTest {
    private static final IndexSource SOURCE = new IndexSource(2, 123, 456);

    @TempDir Path dir;

    private Path write(List<BPlusTreeWriter.Entry> entries, int pageSize) {
        Path file = dir.resolve("t" + pageSize + "-" + entries.size() + ".idx");
        BPlusTreeWriter.write(file, "idx_test", entries, SOURCE, pageSize);
        return file;
    }

    private static long[] search(Path file, KeyRange range) {
        try (BPlusTreeReader reader = BPlusTreeReader.open(file)) {
            return reader.search(range);
        }
    }

    /** Expected answer: rids in key order, then rid order. */
    private static long[] bruteForce(List<BPlusTreeWriter.Entry> entries, KeyRange range) {
        return entries.stream().filter(e -> range.contains(e.key()))
                .sorted((a, b) -> a.key() != b.key() ? Integer.compare(a.key(), b.key())
                        : Long.compare(a.rid(), b.rid()))
                .mapToLong(BPlusTreeWriter.Entry::rid).toArray();
    }

    private static int height(Path file) throws Exception {
        try (var channel = java.nio.channels.FileChannel.open(file)) {
            ByteBuffer header = ByteBuffer.allocate(BPlusTreeFormat.HEADER_SIZE);
            channel.read(header, 0);
            int pageSize = header.getInt(8);
            int page = header.getInt(16);
            int height = 1;
            while (true) {
                ByteBuffer node = ByteBuffer.allocate(pageSize);
                channel.read(node, (long) page * pageSize);
                if (node.getInt(0) == BPlusTreeFormat.LEAF) return height;
                page = node.getInt(8);
                height++;
            }
        }
    }

    @Test void emptyTreeFindsNothingAndKeepsSourceAndName() {
        Path file = write(List.of(), 512);
        assertArrayEquals(new long[0], search(file, KeyRange.all()));
        try (BPlusTreeReader reader = BPlusTreeReader.open(file)) {
            assertEquals(SOURCE, reader.source());
            assertEquals("idx_test", reader.name());
        }
    }

    @Test void rejectsInvalidNamesAndPageSizes() {
        Path file = dir.resolve("bad.idx");
        for (String name : List.of("", "x".repeat(BPlusTreeFormat.MAX_NAME_LENGTH + 1), "zoë")) {
            assertThrows(IllegalArgumentException.class,
                    () -> BPlusTreeWriter.write(file, name, List.of(), SOURCE), name);
        }
        assertThrows(IllegalArgumentException.class,
                () -> BPlusTreeWriter.write(file, "ok", List.of(), SOURCE, 256));
    }

    @Test void singleLeafAnswersPointAndRangeQueries() {
        List<BPlusTreeWriter.Entry> entries = List.of(new BPlusTreeWriter.Entry(5, 50),
                new BPlusTreeWriter.Entry(1, 10), new BPlusTreeWriter.Entry(5, 51),
                new BPlusTreeWriter.Entry(-3, 30));
        Path file = write(entries, BPlusTreeFormat.DEFAULT_PAGE_SIZE);
        assertEquals(1, assertDoesNotThrow(() -> height(file)));
        assertArrayEquals(new long[] {50, 51}, search(file, KeyRange.equalTo(5)));
        assertArrayEquals(new long[] {30, 10}, search(file, new KeyRange(Integer.MIN_VALUE, 4)));
        assertArrayEquals(new long[0], search(file, KeyRange.equalTo(2)));
        assertArrayEquals(new long[0], search(file, new KeyRange(6, 5)));
    }

    @Test void multiLevelTreeMatchesBruteForceForRandomRanges() throws Exception {
        Random random = new Random(4321);
        List<BPlusTreeWriter.Entry> entries = new ArrayList<>();
        for (int i = 0; i < 20000; i++) {
            entries.add(new BPlusTreeWriter.Entry(random.nextInt(400) - 200, i * 17L));
        }
        Path file = write(entries, 512);
        assertTrue(height(file) >= 3, "512-byte pages should force at least three levels");
        assertArrayEquals(bruteForce(entries, KeyRange.all()), search(file, KeyRange.all()));
        for (int i = 0; i < 500; i++) {
            long a = random.nextInt(500) - 250;
            long b = random.nextInt(500) - 250;
            KeyRange range = new KeyRange(Math.min(a, b), Math.max(a, b));
            assertArrayEquals(bruteForce(entries, range), search(file, range), range.toString());
        }
    }

    @Test void longDuplicateListsSpillAcrossLeaves() throws Exception {
        List<BPlusTreeWriter.Entry> entries = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            entries.add(new BPlusTreeWriter.Entry(7, i));
            entries.add(new BPlusTreeWriter.Entry(i % 2 == 0 ? 3 : 9, 1000 + i));
        }
        Path file = write(entries, 512);
        assertTrue(height(file) >= 2);
        assertArrayEquals(LongStream.range(0, 200).toArray(), search(file, KeyRange.equalTo(7)));
        for (KeyRange range : List.of(KeyRange.equalTo(3), KeyRange.equalTo(9), new KeyRange(4, 8),
                new KeyRange(3, 7), new KeyRange(7, 9), KeyRange.all())) {
            assertArrayEquals(bruteForce(entries, range), search(file, range), range.toString());
        }
    }

    @Test void extremeKeysAndBoundsDoNotOverflow() {
        List<BPlusTreeWriter.Entry> entries = List.of(new BPlusTreeWriter.Entry(Integer.MIN_VALUE, 1),
                new BPlusTreeWriter.Entry(0, 2), new BPlusTreeWriter.Entry(Integer.MAX_VALUE, 3));
        Path file = write(entries, 512);
        assertArrayEquals(new long[] {1}, search(file, KeyRange.equalTo(Integer.MIN_VALUE)));
        assertArrayEquals(new long[] {3}, search(file, KeyRange.equalTo(Integer.MAX_VALUE)));
        assertArrayEquals(new long[0], search(file, new KeyRange(Integer.MAX_VALUE + 1L, Integer.MAX_VALUE)));
        assertArrayEquals(new long[] {1, 2, 3}, search(file, KeyRange.all()));
    }

    @Test void rejectsFilesThatAreNotIndexes() throws Exception {
        Path garbage = dir.resolve("garbage.idx");
        Files.write(garbage, new byte[200]);
        assertThrows(StorageException.class, () -> BPlusTreeReader.open(garbage));
        Path truncated = write(List.of(new BPlusTreeWriter.Entry(1, 1)), 512);
        Files.write(truncated, java.util.Arrays.copyOf(Files.readAllBytes(truncated), 100));
        assertThrows(StorageException.class, () -> BPlusTreeReader.open(truncated));
    }
}
