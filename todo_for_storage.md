# Storage follow-up

Completed:

- [x] Handle each supported data type explicitly. `CsvRowCursor` and
  `DirectoryTableStore.validateRow()` now use exhaustive `switch` expressions for
  `INTEGER` and `STRING`, without default branches. Adding another `DataType`
  requires updating both switches before the project compiles. Parsing, value
  validation, and file formats retain their existing behavior.
- [x] Check integration with the new query pipeline: write a table through storage,
  then query its persisted data through a fresh `Engine`, including CSV quoting,
  string identifiers with leading zeros, and integer boundaries.
- [x] Persist typed NULLs with a versioned CSV encoding while retaining legacy
  table semantics, escaped strings, and empty strings. Add NULL predicates and
  three-valued logic for queries over persisted nullable data.
- [x] Provide lazy row/NULL counts and exact small / estimated large distinct
  counts. Refresh automatically on detected external file changes, and expose
  forced `analyze` for edits preserving file metadata. See docs/storage.md.

Questions for the first demo, in priority order:

1. Should the CLI recover from `StorageException` as it already does for syntax
   and planning errors? A nonexistent table, mismatched header, or bad integer
   currently terminates the session. Add CLI tests for continuing with a valid
   query after each failure.
2. Should results include column headers? Current output displays plain values
   and quoted strings. Coordinate headers with the teammate who owns `QueryResult`
   and CLI output; the newer filter branch already implements them.
3. Does the demo need large result sets, or only large input scans? Storage and
   scan/filter/project stream, but `Engine` accumulates all selected rows in
   `QueryResult`. Streaming output is a separate change if large results are part
   of the demo; the existing constrained-heap storage test already verifies large
   input reads and writes.
