package minidb.index;

/**
 * On-disk layout shared by the writer and reader. All pages have the same size.
 *
 * <pre>
 * page 0    header: magic, version, pageSize, columnPosition, rootPage, leafCount, pageCount,
 *                   entryCount (long), csvSize (long), csvModifiedNanos (long),
 *                   nameLength (short), index name (ASCII identifier)
 * leaf      type=0, entryCount, nextLeafPage, then entries: key, ridCount, rid (long) x ridCount
 * internal  type=1, keyCount, child0, then (key_i, child_i) pairs; child_i holds keys >= key_i
 * </pre>
 *
 * Leaves occupy pages 1..leafCount in key order. A duplicate key whose rid list does not fit
 * in one leaf continues as another entry with the same key in the following leaf.
 */
final class BPlusTreeFormat {
    static final int MAGIC = 0x4D444249; // "MDBI"
    static final int VERSION = 2;
    static final int DEFAULT_PAGE_SIZE = 4096;
    /** Every page, including the header page, is at least this large. */
    static final int MIN_PAGE_SIZE = 512;
    static final int HEADER_SIZE = 7 * Integer.BYTES + 3 * Long.BYTES;
    static final int MAX_NAME_LENGTH = 128;
    static final int LEAF = 0;
    static final int INTERNAL = 1;
    /** type, count, and nextLeafPage (leaf) or child0 (internal). */
    static final int NODE_HEADER_SIZE = 3 * Integer.BYTES;
    static final int NO_PAGE = -1;

    private BPlusTreeFormat() {
    }
}
