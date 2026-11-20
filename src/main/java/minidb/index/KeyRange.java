package minidb.index;

/**
 * Inclusive INTEGER key bounds. Longs keep "x > Integer.MAX_VALUE" and similar bounds
 * representable without overflow; such ranges are simply empty.
 */
public record KeyRange(long min, long max) {
    public static KeyRange all() {
        return new KeyRange(Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    public static KeyRange equalTo(int key) {
        return new KeyRange(key, key);
    }

    public KeyRange intersect(KeyRange other) {
        return new KeyRange(Math.max(min, other.min), Math.min(max, other.max));
    }

    public boolean isEmpty() {
        return min > max;
    }

    public boolean contains(int key) {
        return min <= key && key <= max;
    }
}
