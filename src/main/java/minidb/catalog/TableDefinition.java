package minidb.catalog;

/** Schema and its on-disk cell encoding. Legacy schemas have no format marker. */
public record TableDefinition(Table table, Format format) {
    public enum Format { LEGACY_CSV, NULLABLE_CSV_V1 }
}
