package minidb.command;

public final class QueryRequestException extends RuntimeException {
    public QueryRequestException(String message) {
        super(message);
    }
}
