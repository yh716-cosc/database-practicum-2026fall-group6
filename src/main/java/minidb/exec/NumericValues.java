package minidb.exec;

import java.math.BigDecimal;
import minidb.types.DataType;
import minidb.types.Value;

/** Exact ordering across INTEGER, BIGINT, and DECIMAL values. */
final class NumericValues {
    private NumericValues() {}

    /** Compares two non-NULL numeric values without rounding. */
    static int compare(Value left, Value right) {
        if (left.type() == DataType.INTEGER && right.type() == DataType.INTEGER) {
            return Integer.compare((Integer) left.raw(), (Integer) right.raw());
        }
        if (left.type() != DataType.DECIMAL && right.type() != DataType.DECIMAL) {
            return Long.compare(asLong(left), asLong(right));
        }
        return asDecimal(left).compareTo(asDecimal(right));
    }

    private static long asLong(Value value) {
        return ((Number) value.raw()).longValue();
    }

    private static BigDecimal asDecimal(Value value) {
        return value.type() == DataType.DECIMAL ? (BigDecimal) value.raw() : BigDecimal.valueOf(asLong(value));
    }
}
