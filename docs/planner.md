# Query planner

`Planner.plan(statement, catalog)` converts a parsed SELECT tree into a logical
plan. It uses table metadata only; it never scans or evaluates data rows.

1. Validate one FROM table with at most one INNER/LEFT/RIGHT JOIN, optional table aliases,
   and columns or a lone star in SELECT. See [JOIN contracts](join.md).
2. Resolve the table with `Catalog.getTable` and map its case-sensitive column names
   to zero-based indexes and types. For joins, bind the right schema after the
   left schema and require an ON equality between columns from opposite sides.
   Reject duplicate qualifiers and ambiguous unqualified columns; aliases hide
   original table qualifiers.
3. Bind the entire WHERE expression. Comparisons require matching types; integers
   support all six comparison operators, strings only equality and inequality.
   AND/OR/NOT require predicates. Both boolean branches are validated, even when
   runtime evaluation could short-circuit one of them.
4. Bind SELECT columns or expand star in schema order, retaining duplicates.
5. Bind ORDER BY keys against the original schema, then add Sort and Limit.
6. Return `Project(Limit(Sort(Filter(input))))`, omitting absent clauses;
   input is Scan or Join(Scan, Scan).

For schema `id INTEGER, name STRING, age INTEGER`, the query
`SELECT name FROM students WHERE age >= 18` becomes a projection of index 1 over
a filter comparing index 2 to integer 18, over a scan of students. Filtering
happens before projection so WHERE can use columns absent from the output.

Invalid query semantics produce `PlanningException`, which the CLI prints before
continuing. Catalog/storage errors retain their existing exception behavior.
Predicate trees are limited to depth 256 (root depth 0), including flat AND/OR
chains, to protect recursive binding and evaluation from stack overflow.

`Executor.compile` recursively converts Scan, Filter, and Project nodes into
TableScanOperator, FilterOperator, and ProjectOperator objects. It returns a fresh
operator tree without opening a cursor. This is query-plan compilation, not Java
source compilation with javac; it produces no Java source or bytecode.

Engine opens the root, pulls rows with next(), collects the result, and closes the
root. Sort materializes its input; other operators stream. QueryResult holds output rows in
memory. Engine and CLI tests now exercise actual query results. Planner tests
validate logical plans, binding, types, unsupported ASTs, and depth handling.
