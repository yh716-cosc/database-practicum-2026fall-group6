package minidb.storage;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import minidb.types.DataType;

/** Immutable statistics from one complete, validated table scan. Not query results. */
public record TableStatistics(String tableName, long rowCount, long dataBytes,
                              Instant collectedAt, Map<String, ColumnStatistics> columns) {
    public TableStatistics {
        columns = Collections.unmodifiableMap(new LinkedHashMap<>(columns));
    }

    /** distinctCount excludes NULL; consult distinctCountExact before using it as a count. */
    public record ColumnStatistics(DataType type, long nullCount, long distinctCount,
                                   boolean distinctCountExact) {}
}
