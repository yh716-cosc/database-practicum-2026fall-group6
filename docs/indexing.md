# B+ tree indexes

MiniDB can answer selective WHERE predicates on INTEGER columns through an
unclustered B+ tree index instead of scanning the whole CSV. Indexes change only
which records are read; every query returns the same rows, in the same order,
with or without them.

## Create an index

```sql
CREATE INDEX idx_age ON students (age);
```

```text
minidb> CREATE INDEX idx_age ON students (age);
Created index idx_age on students(age)
```

This reads the whole CSV and writes `students.age.idx` next to the table. Index
files are created **only** by CREATE INDEX; SELECT never creates or rebuilds them.

- The column must exist and be INTEGER. Unknown tables or columns, STRING columns,
  and malformed CSV records raise `StorageException`, and no file is written.
- There is one index per column. Running CREATE INDEX again with the same name on
  the same column rebuilds it (`Rebuilt index ...`). Using a name that already
  belongs to another column, or a new name on a column that is already indexed,
  raises `StorageException`; DROP the old index first.
- The name must be an identifier of at most 128 characters. It is stored in the
  file header and shown in messages; the file is named by table and column.
- Multi-column indexes and UNIQUE are not supported. Deleting an `.idx` file by
  hand is safe: queries then scan the table.
- `.idx` files are ignored by Git.

## Drop an index

```text
minidb> DROP INDEX idx_age;
Dropped index idx_age
```

Files are named by table and column, so DROP INDEX opens each `.idx` header in the
data directory to find the one with that name, then deletes it. Unknown names raise
`StorageException`. Only DROP and CREATE INDEX search by name; queries still find an
index with one file check per column. An unreadable index file cannot be dropped by
name (its header cannot be read); delete it by hand or rebuild it with CREATE INDEX.

## List indexes

```text
minidb> SHOW INDEXES;
name    | table    | column | status
--------+----------+--------+-------
idx_age | students | age    | fresh
NULL    | students | id     | unreadable
(2 rows)
```

`SHOW INDEXES` (or `SHOW INDEX`) lists every `.idx` file in the data directory, sorted
by file name. Table and column come from the file name; the name comes from the header.
`status` is `fresh` when queries will use the index, `stale` when the CSV, schema, or
table has changed since the build (queries scan instead; rerun CREATE INDEX), and
`unreadable` when the header cannot be read. An unreadable index has a NULL name and
cannot be dropped by name. SHOW INDEXES never writes or deletes files.

## Out-of-date indexes

The index header records the CSV's size, its modification time, and the column's
schema position at build time. If any of these no longer match, the catalog stops
reporting the index, so queries scan the table and stay correct. The stale file is
left untouched until you run CREATE INDEX again. Unreadable index files are
ignored the same way.

## When an index is used

The optimizer (`Optimizer(Catalog)`) rewrites `Filter(Scan t)` to
`Filter(IndexScan t)` when the top-level AND conjuncts of the predicate include
at least one `column op literal` (or `literal op column`) comparison on a column
with an up-to-date index, where `op` is `=`, `<`, `<=`, `>`, or `>=`. Bounds on the same
column are intersected; a column compared with `=` is preferred. The full
predicate stays in the Filter, so the other conditions still apply.

| WHERE | Access path |
| --- | --- |
| `age = 20` | index on age, key 20 |
| `age >= 18 AND score >= 85 AND age < 30` | index on age, keys 18..29, then filter |
| `18 < age` | index on age, keys 19.. |
| `age = 1 OR age = 2`, `NOT age = 1`, `age <> 3` | full scan |
| `age = id`, `age IS NULL`, unindexed column | full scan |
| any WHERE over a JOIN | full scans (no predicate pushdown yet) |

`new Optimizer()` without a catalog never chooses indexes.

## How it works

**Record ids.** A rid is the byte offset where a record starts in the CSV, found
with Commons CSV's byte tracking (`CsvRows.parser(reader, true)`). Byte tracking
requires an explicit UTF-8 charset; otherwise Commons CSV measures characters with
the platform default and miscounts or fails on non-ASCII text. Offsets handle
quoted commas, escaped quotes, and records spanning several lines.

**File format.** Fixed-size 4096-byte pages (see `BPlusTreeFormat`):

```text
page 0     header: magic, version, page size, column position, root page,
           leaf count, page count, entry count, CSV size, CSV modified time,
           index name
leaf       type, entry count, next leaf page, entries: key, rid count, rids
internal   type, key count, child0, (key_i, child_i) ...; child_i holds keys >= key_i
```

Duplicate keys share one entry with a rid list. A list too long for one leaf
continues as another entry with the same key on the next leaf.

**Build (CREATE INDEX only).** `Engine` hands the statement to
`TableStore.createIndex`, which validates the column and calls `CsvIndexes.build`.
That parses every record (malformed data fails the build), collects `(key, rid)` pairs, and `BPlusTreeWriter`
sorts them and bulk-loads the tree bottom-up: leaves are packed left to right,
then each internal level indexes the first key of every node below it, until a
single root remains. The file is written to a temporary name and moved into place.

**Lookup.** `BPlusTreeReader.search(range)` descends to the leftmost leaf that
may contain the lower bound, following only separators strictly smaller than it
so duplicates spanning leaves are not missed, then walks leaf links until a key
exceeds the upper bound. Each step reads one page.

**Fetch.** `IndexedRowCursor` sorts the matching rids by offset, then seeks to
each record and parses just that record. Sorting keeps results in file order and
reads the file front to back. Records are split directly from an 8 KB byte window
by `CsvRows.parseRecord`, which follows the same RFC 4180 rules as Commons CSV
(checked against it in `CsvRowsTest`) but skips building a parser per row. This
makes index scans faster than a full scan even for wide ranges (on 1M rows,
`age < 90` takes about 540 ms through the index vs about 970 ms for a full scan),
so the optimizer has no selectivity cutoff.

## Classes

| Class | Role |
| --- | --- |
| `sql.ast.CreateIndex` | Parsed `CREATE INDEX name ON table (column)` |
| `sql.ast.DropIndex` | Parsed `DROP INDEX name` |
| `sql.ast.ShowIndexes` | Parsed `SHOW INDEXES` |
| `index.KeyRange` | Inclusive key bounds stored as longs, so `age > 2147483647` is simply empty |
| `index.BPlusTreeWriter` / `BPlusTreeReader` | Bulk load and range search |
| `index.IndexSource` | CSV version and column position stored in the header |
| `storage.CsvIndexes` | `.idx` naming, freshness check, build from CSV, find by name, list files |
| `storage.TableStore.createIndex` / `dropIndex` / `listIndexes` | Validate and build, delete by name, or list as `IndexListing`s; used by `Engine` |
| `catalog.DirectoryCatalog.indexedColumns` | Columns with an up-to-date index file |
| `storage.IndexedRowCursor` | `TableStore.indexScan` cursor |
| `plan.LogicalOperator.IndexScan` | Logical node chosen by `Optimizer` |
| `exec.TableScanOperator.indexScan` | Physical operator over an index cursor |

## Limits

- INTEGER keys only; no composite indexes.
- One index per table access; no index intersection or index-only scans.
- Building holds all `(key, rid)` pairs in memory and sorts them; an index scan
  holds one long per matching record.
- Freshness relies on file size and modification time; a rewrite that preserves
  both is not detected. The database remains static while queries run.
- No index nested-loop join; join inputs are always full scans.

## Tests

`BPlusTreeTest` checks trees against brute-force search, including small pages that
force multi-level trees and duplicate spills. `CreateIndexParserTest` covers syntax.
`IndexScanTest` covers byte offsets, out-of-date and unreadable files, malformed
data, and invalid targets. `IndexSelectionTest` covers optimizer rules.
`IndexedEngineTest` checks CREATE/DROP INDEX messages and name conflicts, that SELECT never writes index
files, the stale-index fallback, and identical results with and without indexes.
