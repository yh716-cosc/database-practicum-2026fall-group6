package minidb.storage;

/** Invalid stored data or an I/O failure, with context suitable for CLI display. */
public final class StorageException extends RuntimeException {
    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
