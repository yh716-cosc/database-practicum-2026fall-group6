package minidb.storage;

/**
 * One index file as SHOW INDEXES reports it. {@code name} is null when the header cannot be
 * read; such an index cannot be dropped by name.
 */
public record IndexListing(String name, String table, String column, Status status) {
    public enum Status {
        /** Built from the current CSV; queries use it. */
        FRESH,
        /** CSV, schema, or table changed since the build; queries scan instead. */
        STALE,
        /** Header unreadable; delete the file by hand or rebuild with CREATE INDEX. */
        UNREADABLE
    }
}
