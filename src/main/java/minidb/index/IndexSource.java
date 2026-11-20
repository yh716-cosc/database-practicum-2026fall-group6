package minidb.index;

/**
 * What an index was built from. A mismatch with the current CSV file or schema position
 * means the index is stale and must be rebuilt before use.
 */
public record IndexSource(int columnPosition, long csvSize, long csvModifiedNanos) {
}
