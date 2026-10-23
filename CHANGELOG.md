# Project updates

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
