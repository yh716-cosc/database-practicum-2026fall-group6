package minidb.types;

/** One cell. raw matches type, or is null for a typed SQL NULL in query results. */
public record Value(DataType type, Object raw) {
    public static Value nullValue(DataType type) {
        return new Value(java.util.Objects.requireNonNull(type), null);
    }

    public boolean isNull() { return raw == null; }
}
