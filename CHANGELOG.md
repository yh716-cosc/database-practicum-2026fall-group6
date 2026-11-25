# Project updates

## 2026-11-25 — Aggregation and GROUP BY (Task 3)

- Parse `COUNT(*)`, `COUNT(column)`, `SUM`, `AVG`, `MIN`, `MAX` in SELECT, HAVING, and
  ORDER BY; add `GROUP BY` columns and `HAVING` between WHERE and ORDER BY. Aggregate
  names are functions only in call position.
- Plan aggregate queries as Scan/JOIN -> WHERE -> Aggregate -> HAVING -> Sort -> Limit
  -> Project. Reject ungrouped columns, aggregates in WHERE/ON, SUM/AVG over STRING,
  and HAVING without grouping. Equivalent calls share one slot; HAVING/ORDER BY-only
  aggregates are hidden from output.
- Add `AggregateOperator` (blocking hash aggregation with per-group accumulators),
  result-only `BIGINT`/`DECIMAL` types, exact sums with BIGINT overflow errors, and AVG
  at 6 digits HALF_EVEN. Storage rejects result-only types.
- HAVING compares INTEGER/BIGINT/DECIMAL exactly; sorting supports the new types.
- Optimizer and cost-based join choice recurse through the aggregate boundary.
- Add docs/aggregation.md, a two-table demo fixture in `local-test-data/aggregation`,
  and 54 tests.

## 2026-11-13 — Statistics commands and cached-only access

- Add `.stats <table>` and `.analyze <table>` at the CLI prompt, with explicit
  full-scan/cache messages, row and NULL counts, CSV size, collection time, and
  distinct counts labeled exact or estimated.
- Expose statistics operations through Engine, sharing its query storage cache.
- Add optional cached-only lookup that checks file/schema metadata without reading
  CSV rows; stale or unavailable entries are evicted without automatic collection.
- Retain forced refresh for metadata-preserving edits and prompt recovery after
  malformed commands or failed collection. Ordinary queries do not collect stats.

## 2026-10-30 — Repair nullable-storage/JOIN merge

- Remove duplicated predicate methods, IsNull records, and malformed test nesting.
- Preserve constant predicates, three-valued logic, and all merged test cases.
- Distinguish typed NULL from literal NULL strings while retaining headers and escaping.
- Make nullable schema/output assertions portable across LF and CRLF platforms.
- Reconcile supported-query and nullable-storage documentation.

## 2026-10-30 — Unclustered B+ tree indexes

- Add `CREATE INDEX name ON table (column)` for INTEGER columns; the CLI prints
  `Created index ...` or, for an existing index, `Rebuilt index ...`.
- Add `DROP INDEX name`, which finds the index file by the name in its header and
  deletes it. CREATE INDEX now rejects a name used on another column and a second
  name on an already indexed column.
- Add `SHOW INDEXES`, which lists each index file's name, table, column, and status
  (`fresh`, `stale`, or `unreadable`), so forgotten names can be passed to DROP INDEX.
- Bulk-load page-based B+ tree files (`<table>.<column>.idx`) keyed by column value,
  with CSV record byte offsets as rids. Only CREATE INDEX builds them; queries ignore
  an index whose CSV has changed and scan the table instead.
- Optimizer rewrites `Filter(Scan)` to `Filter(IndexScan)` for `=`, `<`, `<=`, `>`, `>=`
  conjuncts against literals; the full predicate is still applied.
- Index scans return rows in file order, so results match full scans exactly.
- Share CSV record conversion between scans, index builds, and index fetches.
- See docs/indexing.md for the file format, rules, and limits.
- Add psql-style `\timing [on|off]` to the CLI; when on, each successful statement
  is followed by `Time: <ms> ms` (parse, plan, and execution; printing excluded).

## LEFT and RIGHT JOIN extension

- Parse LEFT/RIGHT JOIN with optional OUTER and preserve the logical column layout.
- Extend the tuple nested-loop operator to null-extend unmatched preserved rows.
- Execute RIGHT joins with swapped physical inputs, without rebinding logical indexes.
- Add typed query NULLs, SQL three-valued predicates, IS NULL/IS NOT NULL, NULL sorting and display.
- Keep CSV storage unchanged and keep standalone NULL literals unsupported.
- Add outer-join, null-logic, failure, and lifecycle regression tests.

## 2026-10-29 — Two-table INNER JOIN

- Add table aliases and a single two-table column-equality INNER JOIN.
- Bind qualified and unqualified names against the combined schema; reject ambiguity.
- Execute streaming tuple nested-loop joins using fresh right subtrees per left row.
- Preserve duplicate matches, output labels, filtering, sorting, and limits.
- Add integration and lifecycle tests covering EOF, early close, failures, and cleanup.
- See docs/join.md for supported syntax, performance, and error visibility.

## 2026-10-23 — ORDER BY and LIMIT

- Parse optional ORDER BY column lists with ASC/DESC and non-negative LIMIT.
- Bind sort keys before projection, including columns absent from SELECT.
- Add stable in-memory SortOperator and streaming LimitOperator with cleanup.
- Optimizer merges adjacent limits and identical adjacent sorts.
- Document early termination, LIMIT 0, memory use, and file responsibilities in
  docs/sort-limit.md.

## 2026-10-23 — CLI recovery and readable query results

- CLI displays StorageException messages and continues accepting statements.
- Query results carry output column names, including star expansion, reordered
  and repeated columns, and empty results.
- Print headers and raw values; show empty strings as `""` and escape backslashes,
  line breaks, tabs, and pipes to keep each row on one line.
- Added recovery, result metadata, and output-format regression tests.

## 2026-10-23 — Rule-based optimizer v1

- Added optimization between planning and execution.
- Fold literal comparisons and constant boolean branches; remove double negation
  and true filters; compose consecutive projections.
- Preserve planning validation, row order/duplicates, and storage errors under
  false filters. No statistics or cost-based optimization yet.

## 2026-10-11 — Single-table query execution

Current working-tree milestone; this entry does not imply a published release.

### Implemented

- Connected tokenizer/parser, planner, executor, and CSV storage through Engine.
- Added column binding, type validation, star expansion, and logical plans.
- Converted Scan/Filter/Project plans into fresh, lazily opened operator trees.
- Added typed comparisons, boolean short-circuit evaluation, streaming operators,
  and resource cleanup on EOF, early close, and runtime failures.
- Made the CLI recover from syntax and planning errors.
- Added shared synthetic students data with reproducible SQL examples.

### Validation

- Most recent full test run: 170 tests passed, no failures or skipped tests.
- Built the packaged JAR and manually checked filtering, quoted/empty strings,
  integer comparison, and continuing after a type error with the shared sample.

### Remaining limitations

- Single-table read-only SQL; no JOIN, aliases, sorting, aggregation, or SQL writes.
- Storage errors still propagate out of the CLI.
- Results accumulate in memory and print using Java Value representations.
- Parser and planner enforce expression-depth limits; no query optimizer.

See README.md for startup, data formats, supported SQL, and troubleshooting.
