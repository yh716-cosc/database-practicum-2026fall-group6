# Query filtering — Version 1

Status: implemented for single-table SELECT/FROM/WHERE. Parser, planner, executor,
and scan/filter/project operators are connected through Engine. See the
[quick start](../README.md) and [shared sample queries](../local-test-data/README.md).

## Scope

One table or a two-table INNER/LEFT/RIGHT equijoin with optional table aliases;
SELECT star or ordered/repeated columns; optional WHERE;
INTEGER and STRING operands; comparisons, NOT, AND, OR, and parentheses.
Integer comparisons support all six operators; strings support only equality and
inequality. Typed stored NULLs and IS NULL / IS NOT NULL are supported. No implicit
conversion, standalone NULL literals, grouping, aggregation, arithmetic, or writes
through SQL. ORDER BY and LIMIT are supported; see [sort and limit](sort-limit.md).
Full grammar is in [parser.md](parser.md).

## Component contracts

| Component | Input | Output / responsibility |
| --- | --- | --- |
| SqlParser | SQL text | Query with unbound Expression nodes; syntax validation |
| Planner | Query and Catalog | LogicalOperator tree with typed BoundExpression nodes |
| Executor.compile | LogicalOperator tree | Fresh Operator tree, with no data cursor opened |
| TableScanOperator | TableStore and table name | Typed rows in schema order |
| FilterOperator | Child and bound predicate | Original matching rows, preserving duplicates and order |
| ProjectOperator | Child and column indexes | Selected values in requested order, including repeats |
| Engine | SQL | QueryResult containing all output rows |

Storage prepares typed rows from explicit schema/CSV pairs. Parser does not read
these files. Planner resolves metadata but does not scan rows. Filter evaluates
already typed values; it never parses CSV fields.

## Binding and logical plan

Planner validates one FROM table, empty joins, no aliases, valid SELECT items,
and a predicate-shaped WHERE, including for directly constructed ASTs. It maps
case-sensitive column names to schema indexes, verifies actual table qualifiers,
and checks all predicate branches before execution. Runtime short-circuiting
never excuses a type error or unknown column.

The actual bound node types are nested in `BoundExpression`:

- Scalar: `Column(index, type)` and `Literal(Value)`.
- Predicate: `Comparison`, `IsNull`, `And`, `Or`, and `Not`.

INTEGER/INTEGER supports `= <> < <= > >=` using signed numeric comparison.
STRING/STRING supports `= <>` with exact String equality (no trimming or Unicode
normalization). Mixed types fail planning. Boolean results are intermediate
predicates, not a storage data type.
Comparisons with NULL yield UNKNOWN. AND/OR/NOT follow SQL three-valued logic;
WHERE retains only TRUE. IS NULL / IS NOT NULL always yield TRUE or FALSE.

For the shared schema `id, name, age, score`:

```sql
SELECT name FROM students WHERE age >= 18;
```

```text
LogicalOperator.Project([1])
  LogicalOperator.Filter(Column(2, INTEGER) >= Literal(18))
    LogicalOperator.Scan("students")
```

Filter precedes projection, so WHERE can reference omitted columns. Without
WHERE, Planner omits Filter. Star expands in schema order. Engine optimizes the
bound plan before compiling it.
See [planner.md](planner.md) for binding details and tree-depth limits.

## Execution and lifecycle

Executor recursively maps logical Scan, Filter, and Project nodes to the matching
operator classes. This creates Java objects, not Java source or bytecode. Unknown
or null plan nodes raise IllegalArgumentException. Recompiling creates an
independent tree for another execution.

Engine calls open(), repeatedly calls next() until null, then closes the root.
TableScanOperator acquires its cursor on open. Filter pulls until a row matches;
AND/OR evaluation short-circuits. Project constructs a new row using selected
value references. Output order and duplicate rows are preserved.

Operators are single-use: open once, then read until EOF or explicit close.
Repeated next() at EOF returns null until explicitly closed. Explicit close is
idempotent and prohibits further reads/reopening. Wrappers own and close their
children. Runtime acquisition/open/read/evaluation failures close owned resources;
cleanup errors are suppressed on the original failure. Cleanup is attempted once.
Operators cover open failures even though Engine opens the root before its try block.

Operators stream input, but Engine collects the entire result in QueryResult;
memory therefore grows with output size. Result printing includes column names.
Empty strings display as `""`; backslashes, line breaks, tabs, and pipes are escaped.
Typed NULLs print as `NULL`. Literal STRING NULL and strings beginning with a quote
are quoted with doubled quote escaping; other strings retain raw-value display.

## Errors

| Stage | Exception | CLI behavior |
| --- | --- | --- |
| Syntax, unsupported SQL | SqlParseException | Print error and accept next line |
| Unknown column/qualifier, type error, unsupported AST | PlanningException | Print error and accept next line |
| Missing table files, malformed schema/CSV, I/O | StorageException | Print error and accept next line |

Catalog failures during planning also retain StorageException. Malformed rows
are never silently skipped. The CLI catches storage errors and continues accepting statements.

## Verification

```sh
mvn -q test
mvn -q package
java -jar target/minidb-0.1.0.jar local-test-data
```

The shared sample README provides exact expected outputs. Automated fixtures are
independent of that sample. Tests cover syntax and precedence; name/type binding;
invalid direct ASTs; integer boundaries and exact string comparisons; boolean
short-circuiting; scan/filter/project lifecycle and failure cleanup; selection
order and duplicates; empty results; repeated execution; full SQL results; and
CLI recovery after parse/planning errors.

## Future extensions

JOIN requires alias/ambiguity handling and column positions for combined rows.
GROUP BY needs a separate operator and plan; ORDER BY uses SortOperator. Further output formatting,
and streaming final results can be tackled without
expanding SQL syntax. See [project updates](../CHANGELOG.md) for the current milestone.

## Rule-based optimizer v1

`Optimizer.optimize(plan)` rewrites fully bound, type-checked plans without storage
reads or input mutation. Engine invokes it between Planner and Executor. Rules
fold literal comparisons, simplify constant AND/OR/NOT branches, remove double
negation and always-true filters, and compose consecutive projections while
preserving column order and repeats. Internal boolean constants are predicates,
not a SQL or storage data type.

Always-false filters retain their child scan so malformed CSV still raises
StorageException. All branches are validated before optimization, including
unreachable branches. Row order and duplicates are unchanged. There is no cost
model, JOIN reordering, predicate pushdown, or storage-level column pruning.
Current single-table plans already place filters below projections; future
pushdown rules must account for column-index remapping.
