package minidb.storage;

/** A scan that yields one row at a time and must not load the whole file. */
public interface RowCursor extends AutoCloseable {
    void open();

    /** Next row, or null when the scan is finished. */
    Row next();

    @Override
    void close();
}
