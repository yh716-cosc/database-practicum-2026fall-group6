package minidb.exec;

/** A recoverable runtime query failure, such as an aggregate result outside its type's range. */
public final class QueryExecutionException extends RuntimeException {
    public QueryExecutionException(String message) {
        super(message);
    }

    public QueryExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
