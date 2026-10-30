# Project updates

## 2026-10-30 — Nullable storage and table statistics

- New writes use a versioned CSV encoding that distinguishes typed NULLs, empty
  strings, literal NULL text, and escaped backslashes. Unmarked tables keep their
  original interpretation; schemas and existing table identities remain compatible.
- Add IS NULL / IS NOT NULL, three-valued predicate evaluation, and NULL output
  so stored nullable rows can be queried on main as well as the newer query branch.
- Add optional statistics/analyze storage APIs: exact row and NULL counts, CSV
  size, collection timestamp, and exact small / estimated large distinct counts.
- Collect lazily into a per-store memory cache; refresh on CSV/schema metadata or
  definition changes. Explicit analyze handles timestamp/size-preserving edits.
  Failed refreshes evict old entries, and detected mid-collection changes fail.
- Cover format compatibility, SQL NULL logic, statistics accuracy and refresh,
  failed scans, and large-input statistics under a constrained heap.

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
