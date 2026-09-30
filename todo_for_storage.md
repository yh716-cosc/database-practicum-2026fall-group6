# Storage questions

1. Should reading and writing use exhaustive `switch` expressions for data types?

   Currently, `CsvRowCursor` handles `INTEGER` explicitly and otherwise treats the
   field as text. `DirectoryTableStore.validateRow()` similarly assumes that any
   non-integer type is `STRING`. This works with the two current types, but adding
   a type such as `FLOAT` would require updating both places.

   Consider exhaustive `switch` expressions without a default branch so the
   compiler flags missing cases when a new `DataType` is added. Keep the current
   behavior for now; revisit before adding more types.
