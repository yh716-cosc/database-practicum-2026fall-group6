package minidb.exec;

/** SQL predicate results; only TRUE passes WHERE. */
public enum TruthValue {
    TRUE, FALSE, UNKNOWN;

    public static TruthValue of(boolean value) { return value ? TRUE : FALSE; }

    public TruthValue not() {
        return this == UNKNOWN ? UNKNOWN : of(this == FALSE);
    }

    public TruthValue and(TruthValue other) {
        if (this == FALSE || other == FALSE) return FALSE;
        return this == UNKNOWN || other == UNKNOWN ? UNKNOWN : TRUE;
    }

    public TruthValue or(TruthValue other) {
        if (this == TRUE || other == TRUE) return TRUE;
        return this == UNKNOWN || other == UNKNOWN ? UNKNOWN : FALSE;
    }
}
