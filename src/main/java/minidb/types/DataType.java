package minidb.types;

/**
 * Value types. INTEGER and STRING are storage types that may appear in schema files.
 * BIGINT and DECIMAL are query-result types produced only by aggregates
 * (COUNT/SUM and AVG); storage rejects them as column types and write inputs.
 *
 * <p>Raw representations: INTEGER {@link Integer}, STRING {@link String},
 * BIGINT {@link Long}, DECIMAL {@link java.math.BigDecimal}; null raw means typed NULL.
 */
public enum DataType {
    INTEGER,
    STRING,
    BIGINT,
    DECIMAL;

    /** True for types that schema files and CSV storage accept. */
    public boolean isStorable() {
        return this == INTEGER || this == STRING;
    }

    public boolean isNumeric() {
        return this != STRING;
    }
}
