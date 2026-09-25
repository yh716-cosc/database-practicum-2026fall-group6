package minidb.types;

/** One cell. `raw` is an Integer or a String, matching `type`. */
public record Value(DataType type, Object raw) {
}
