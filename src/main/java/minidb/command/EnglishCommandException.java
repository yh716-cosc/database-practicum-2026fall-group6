package minidb.command;

/** Raised when input does not match the supported deterministic command grammar. */
public final class EnglishCommandException extends RuntimeException {
    public EnglishCommandException(String message) {
        super(message);
    }
}
