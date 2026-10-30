package minidb.storage;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import minidb.catalog.Table;
import minidb.storage.TableStatistics.ColumnStatistics;
import minidb.types.Value;

/** One-pass collection with bounded distinct-value state per column. */
final class StatisticsCollector {
    private final Table table;
    private final long[] nullCounts;
    private final DistinctCounter[] distinct;
    private long rows;

    StatisticsCollector(Table table) {
        this.table = table;
        nullCounts = new long[table.columns().size()];
        distinct = new DistinctCounter[nullCounts.length];
        for (int i = 0; i < distinct.length; i++) distinct[i] = new DistinctCounter();
    }

    void add(Row row) {
        rows++;
        for (int i = 0; i < distinct.length; i++) {
            Value value = row.values().get(i);
            if (value.isNull()) nullCounts[i]++;
            else distinct[i].add(value.raw());
        }
    }

    TableStatistics finish(long dataBytes) {
        var columns = new LinkedHashMap<String, ColumnStatistics>();
        for (int i = 0; i < distinct.length; i++) {
            var column = table.columns().get(i);
            columns.put(column.name(), new ColumnStatistics(column.type(), nullCounts[i],
                    distinct[i].count(rows - nullCounts[i]), distinct[i].exact != null));
        }
        return new TableStatistics(table.name(), rows, dataBytes, Instant.now(), columns);
    }

    /**
     * Exact through 256 distinct values, then 1,024-register HyperLogLog (~3.25%
     * relative standard error, not an error bound). See Flajolet et al., 2007:
     * https://algo.inria.fr/flajolet/Publications/FlFuGaMe07.pdf
     */
    private static final class DistinctCounter {
        private static final int PRECISION = 10;
        private static final int REGISTERS = 1 << PRECISION;
        private final byte[] registers = new byte[REGISTERS];
        private Set<Object> exact = new HashSet<>();

        void add(Object value) {
            if (exact != null) {
                exact.add(value);
                if (exact.size() > 256) exact = null;
            }
            long hash = hash(value);
            int bucket = (int) hash & (REGISTERS - 1);
            int rank = Long.numberOfLeadingZeros(hash >>> PRECISION) - PRECISION + 1;
            registers[bucket] = (byte) Math.max(registers[bucket], rank);
        }

        long count(long nonNullRows) {
            if (exact != null) return exact.size();
            double sum = 0;
            int zeros = 0;
            for (byte register : registers) {
                sum += Math.scalb(1.0, -register);
                if (register == 0) zeros++;
            }
            double estimate = (0.7213 / (1 + 1.079 / REGISTERS)) * REGISTERS * REGISTERS / sum;
            if (estimate <= 2.5 * REGISTERS && zeros > 0) {
                estimate = REGISTERS * Math.log((double) REGISTERS / zeros);
            }
            return Math.min(nonNullRows, Math.max(257, Math.round(estimate)));
        }

        private static long hash(Object value) {
            long hash;
            if (value instanceof Integer integer) {
                hash = integer.longValue();
            } else {
                // Hash the full string, not String.hashCode(), whose collisions are common.
                String text = (String) value;
                hash = 0xcbf29ce484222325L;
                for (int i = 0; i < text.length(); i++) {
                    hash = (hash ^ text.charAt(i)) * 0x100000001b3L;
                }
            }
            // 64-bit avalanche so both the bucket and leading-zero bits are distributed.
            hash ^= hash >>> 33;
            hash *= 0xff51afd7ed558ccdL;
            hash ^= hash >>> 33;
            hash *= 0xc4ceb9fe1a85ec53L;
            return hash ^ (hash >>> 33);
        }
    }
}
