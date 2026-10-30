# Storage layer

This component moves table data from CSV files into typed Java rows, and writes typed
rows back to new CSV tables. Both directions work incrementally. SQL parsing, query
operators, and natural-language translation are separate components.

## Files and types

Each table uses two files in the database directory:

```text
data/
  students.csv
  students.schema
```

`students.schema` declares column order and types, one `columnName TYPE` per line:

```text
id INTEGER
name STRING
age INTEGER
```

`students.csv` starts with the corresponding header:

```csv
id,name,age
1,Alice,20
2,"Smith, Bob",22
```

Rules shared by both storage formats:

- Files use UTF-8 without a byte-order mark. CSV uses Apache Commons CSV's RFC4180
  format, supporting quoted commas, escaped double quotes, and embedded newlines.
- Every CSV requires a header matching the schema exactly, including order and case.
- Table and column identifiers match `[A-Za-z_][A-Za-z0-9_]*` and are case-sensitive.
- Schemas require at least one column and reject duplicate names. Blank schema lines
  are ignored. Type names must be uppercase `INTEGER` or `STRING`.
- `INTEGER` is a signed 32-bit Java `Integer`. Optional `+`/`-` signs are accepted;
  spaces, fractions, empty fields, and overflow are errors.
- `STRING` preserves contents, including spaces, empty strings, and leading zeros.
  The text `NULL` is an ordinary string. Typed SQL NULLs use the versioned encoding below.
- Each record must have exactly the declared number of fields. Invalid records fail
  the scan; they are not silently skipped. Blank CSV lines are records, not comments.
- A header-only CSV is a valid empty table. A completely empty CSV is invalid.
- Data record numbers in errors start at 1 after the header. They are not physical
  line numbers because one quoted record can span multiple lines.

Explicit schemas avoid guessing whether values such as `00123` are numbers or text.
They are storage metadata, not SQL parser configuration. This format can be revised
later if the team chooses another catalog representation.

CSV conversion and write validation handle `INTEGER` and `STRING` with exhaustive
`switch` expressions. Adding a new `DataType` requires updating both switches;
unsupported new types cannot silently fall through to string handling.

## Nullable CSV format and compatibility

Schemas without a format marker retain the original interpretation: every STRING
field is literal text, including `\N`, and empty/invalid INTEGER fields are errors.
Existing tables are never silently upgraded. Every newly written table starts its
schema with this marker, before any columns:

```text
# minidb-format nullable-csv-v1
id INTEGER
name STRING
```

In that format, decoding happens after ordinary RFC4180 CSV parsing:

| Parsed CSV field | Typed cell |
| --- | --- |
| `\N` | NULL of the declared INTEGER or STRING type |
| `\\N` | Literal STRING `\N` |
| `\\path` | Literal STRING `\path` |
| empty field | Empty STRING; invalid INTEGER |
| `NULL` | Literal STRING `NULL`; invalid INTEGER |

For non-null strings beginning with a backslash, the writer prefixes one extra
backslash; the reader removes it. Any other single-backslash prefix is invalid in
this version. CSV quoting is a separate layer: even a quoted `\N` means NULL, so a
literal token must use the backslash escape. Unknown versions, repeated markers,
and markers after column declarations fail rather than being guessed.

Use `Value.nullValue(DataType.INTEGER)` or `Value.nullValue(DataType.STRING)` to
construct typed NULL cells. Their type must still match the schema. A missing
`Value` object is an error. `Value.isNull()` tests the payload. `Catalog.getTable`
continues returning the same column schema; `getTableDefinition` additionally
returns the disk format. Custom catalogs default to legacy CSV unless they override
that method to supply `NULLABLE_CSV_V1`.

SQL can select stored NULLs and test `column IS NULL` / `IS NOT NULL`. Comparisons
with NULL yield UNKNOWN; AND/OR/NOT use SQL three-valued logic, and WHERE retains
only TRUE. Output prints a NULL cell as `NULL`, distinct from the quoted string
`'NULL'`. Standalone NULL literals such as `age = NULL` remain unsupported.

## Read disk data into memory

```java
TableStore store = new DirectoryTableStore(Path.of("data"));
try (RowCursor cursor = store.scan("students")) {
    cursor.open();
    Row row;
    while ((row = cursor.next()) != null) {
        // Values follow schema order: id, name, age.
        Integer id = (Integer) row.values().get(0).raw();
        // Process this row here; do not accumulate all rows for a streaming scan.
    }
}
```

Imports are `java.nio.file.Path` and `minidb.storage.*`.

`scan()` resolves metadata and returns an independent cursor. `open()` opens the CSV
and checks the header. Each `next()` parses and converts one record. End-of-file
returns `null` and closes the file automatically; repeated `next()` calls at EOF
also return `null` until explicitly closed. Try-with-resources handles early exits.

Cursors are single-use and not thread-safe. Reading before `open()` or after explicit
`close()`, or opening a cursor twice, raises `IllegalStateException`. `close()` is
idempotent. To restart a scan, request a new cursor. Data errors raise
`StorageException` and close the reader. I/O errors retain their original cause.

Memory consists of schema metadata, the CSV reader's buffer, and the current record.
It does not grow with the number of records; a very large individual record still
requires enough memory to parse that record.

## Write memory data to disk

```java
Table table = new Table("students", List.of(
    new Column("id", DataType.INTEGER),
    new Column("name", DataType.STRING)));

Iterable<Row> rows = List.of(
    new Row(List.of(new Value(DataType.INTEGER, 1),
                    new Value(DataType.STRING, "Alice"))),
    new Row(List.of(new Value(DataType.INTEGER, 2),
                    new Value(DataType.STRING, "Smith, Bob"))));

TableStore store = new DirectoryTableStore(Path.of("data"));
store.write(table, rows);
```

Additional imports: `java.util.List`, `minidb.catalog.*`, and `minidb.types.*`.

`write()` creates the output directory if needed and writes both files. An `Iterable`
can produce rows lazily, so a large export does not require a list. Each cell must
have the declared type and a matching Java value (`Integer`, `String`, or typed NULL).
The CSV printer escapes contents; callers should supply the original strings.

Writing rejects an existing CSV **or** schema file. There is no overwrite, append,
update, or transaction API. Use a new table name or output directory for an export.
The method does not close a caller-owned source: callers must close source cursors
or streams themselves, including when writing fails.

Writes first use temporary files in the output directory. Once serialization and
validation finish, the CSV is moved into place, followed by the schema. Ordinary
exceptions trigger cleanup, including rollback of a CSV published before a failed
schema move. Cleanup failures are attached as suppressed exceptions.

This is not a crash-atomic two-file transaction: a process or machine crash between
moves can leave an orphan CSV or temporary files. Concurrent writers and database
changes during queries are outside this static-database design. Prepare tables
before running queries.

## Table statistics and external edits

Statistics are optional Java storage API operations; there is no SQL ANALYZE
statement, persistent `.stats` file, or optimizer integration yet:

```java
TableStore store = new DirectoryTableStore(Path.of("data"));
TableStatistics stats = store.statistics("students");
long rows = stats.rowCount();
TableStatistics.ColumnStatistics names = stats.columns().get("name");
long nullNames = names.nullCount();
long distinctNames = names.distinctCount();
boolean exact = names.distinctCountExact();

// Force a fresh complete scan after an external edit, regardless of timestamps.
TableStatistics refreshed = store.analyze("students");
```

Both methods fully parse and validate every record when collecting; they return
exact row counts (excluding the header), per-column NULL counts, CSV byte size,
column types, and a collection timestamp. Quoted multiline fields count as one
record. `distinctCount` excludes NULL, but includes empty strings and literal
`NULL` strings. It is exact through 256 distinct non-null values per column.
Larger sets use a 1,024-register HyperLogLog estimate, flagged with
`distinctCountExact == false`. Its expected relative standard error is about
3.25%, not a guaranteed error bound; results are capped at the non-null row count.
The estimator follows [Flajolet et al. (2007)](https://algo.inria.fr/flajolet/Publications/FlFuGaMe07.pdf).
Distinct-value state retains at most 256 actual values plus 1 KB of registers per
column, then discards actual values on promotion. It does not retain every row or
every distinct value. Each returned statistics object and column map is immutable.

Collection and refresh policy:

- Reads and writes do not automatically collect statistics. The first
  `statistics(name)` call performs a full scan and caches the result in that store
  instance. A fresh store collects again; partial scans never produce row counts.
- Before reusing a cache entry, `statistics` checks both CSV and schema file sizes,
  full-resolution modification times, creation times, file identities (where the
  filesystem supplies them), and the current parsed table definition. Changes
  trigger a full recollection. This includes external appends, ordinary edits,
  file replacements, and schema-only edits.
- File metadata is a change detector, not a content checksum. An in-place CSV edit
  that preserves size and timestamps can be invisible to the cache. After editors
  or copy tools that preserve these attributes, call `analyze(name)`. That method
  always scans, even if all metadata matches. Previously returned snapshots remain
  unchanged; callers request statistics again when they need updated values.
- Collection checks files/definition again after scanning. Detected concurrent
  edits raise `StorageException`; malformed data, missing files, and any failed
  refresh evict the old entry and never return stale or partial counts as fallback.
  Fix the input and retry. This is best-effort change detection, not a filesystem
  snapshot: finish external edits before running queries or collecting statistics.
- Statistics do not change query correctness, skip validation, or select an index.
  A malformed record remains an error even if a prior count was available.

## Team integration

- `DirectoryCatalog.getTable(name)` reads a schema and checks that its CSV exists.
  `tables()` discovers `.schema` files in sorted order and validates their pairs.
  A CSV without a schema is not discovered; a schema without a CSV raises an error.
  An existing empty directory has no tables; a missing directory raises an error.
- `DirectoryTableStore(Path, Catalog)` permits the team to supply another catalog.
  The default constructor uses `DirectoryCatalog`. `Engine` now shares one catalog
  instance with its store. The CSV header must still match the supplied metadata.
- TableScanOperator delegates `open/next/close` to a `RowCursor`.
  Filtering, projection, joins, sorting, and grouping belong above storage.
- Each call to `scan()` creates its own file position, including scans of the same
  table. This supports repeated scans and self-joins.
- The CLI prints `SqlParseException` and `PlanningException`, then reads the
  next line. `StorageException` still propagates and ends the CLI session.
- `Engine` still accumulates output rows in `QueryResult`. Streaming storage does
  not yet make the entire query pipeline bounded in memory.

## Run and verify

```bash
mvn -q test
mvn -q package
java -cp target/minidb-0.1.0.jar minidb.storage.StorageDemo target/storage-demo
```

Use a fresh demo directory if `students` already exists. The demo bypasses the
SQL query pipeline, writes sample rows, and reads them back.

The storage tests cover round trips, externally authored CSV, quoting, Unicode,
empty tables, malformed input, integer overflow, schema validation, cursor lifecycle,
independent scans, overwrite refusal, and cleanup after failed writes. A subprocess
with a 32 MB heap writes and validates 120,000 rows totaling over 100 MB on disk.
It consumes rows immediately in both directions and checks every value.

Apache Commons CSV 1.14.1 handles CSV syntax; our code handles database types, schemas,
file naming, and cursor lifecycle. Maven Shade bundles runtime dependencies so the
existing `java -jar` launch and the storage demo work from the packaged JAR.

Library reference: [Apache Commons CSV](https://github.com/apache/commons-csv).
