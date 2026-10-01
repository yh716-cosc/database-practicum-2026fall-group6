# Query Filtering — Version 1 Design

Status: Design draft; not implemented. New class names and interfaces in this document are proposed contracts.

## 1. Goals and Scope

Implement the complete single-table `SELECT / FROM / WHERE` pipeline: parse SQL, validate columns and types, scan data, filter rows, and return selected columns.

Version 1 supports signed 32-bit integers only. This document calls the type `INT`; code and `.schema` files retain the existing names `DataType.INTEGER` and `INTEGER`, with Java `Integer` values.

Supported features:

- One base table, without table or column aliases.
- `SELECT *` or an explicit column list, preserving selection order and allowing repeated columns.
- An optional `WHERE` clause; omitting it returns all rows.
- Integer literals, column references, and comparisons between columns.
- Comparison operators: `=`, `<>`, `<`, `<=`, `>`, `>=`.
- Boolean operators: `AND`, `OR`, `NOT`, and parentheses for grouping predicates.
- Unqualified column names and names qualified by the actual table name, such as `age` and `students.age`.

Deferred features: string queries, SQL `NULL`, arithmetic expressions, type conversion, JOIN, multiple FROM tables, ORDER BY, GROUP BY, aggregation, HAVING, DISTINCT, LIMIT, subqueries, indexes, and filter pushdown.

Existing storage support for `STRING` remains available. To make Version 1 behavior explicit, planning rejects a target table containing any non-`INTEGER` column, even if that column is not selected. A later version may relax this restriction.

## 2. Existing Code

| Component | Current state | Version 1 changes |
| --- | --- | --- |
| `Engine` | Connects parse → plan → compile → execute | Review error paths and resource cleanup |
| `SqlParser` | Not implemented | Implement the supported SQL subset |
| `Query` | Contains select, from, joins, and where | Retain structure; null where means no filter |
| `Expression` / `ColumnRef` | Expression interface and column references exist | Add integer, comparison, and logical expressions |
| `Planner` / `LogicalOperator` | Not implemented | Add column binding, type checking, and logical plans |
| `Executor` / `Operator` | Compilation is not implemented; lifecycle interface exists | Add Scan, Filter, and Project |
| `Catalog` / `TableStore` | Schema lookup and streaming scans exist | Reuse directly |
| `Row` / `Value` | Store typed values in schema order | Reuse directly |

## 3. Data Parsing and Query Parsing Boundaries

Data preparation and query execution are separate flows:

```text
Data preparation:
Explicit schema + CSV → Catalog / TableStore → Typed Row

Query execution:
SQL → Parser → AST → Planner + Catalog → Logical Plan
                                              ↓
                  QueryResult ← Operators ← Executor
```

- `.schema` defines column names, order, and types. Version 1 does not infer schemas from CSV contents.
- The SQL parser handles syntax. It does not read CSV data or determine whether a column exists.
- The Planner resolves columns and checks types using the catalog without scanning data.
- Storage converts CSV fields into integer rows according to the schema. Invalid integers, overflow, or malformed records fail the scan.
- Filter operates on typed rows. It does not parse CSV files or create tables.

The team notes' “creating table based on data that is parsed” belongs to data preparation. Before a Version 1 query starts, the target table must have valid `.schema` and `.csv` files.

## 4. SQL and Expression Contracts

Example:

```sql
SELECT id
FROM students
WHERE age >= 18 AND score > 80;
```

Use the following minimal grammar. Keywords are case-insensitive; table and column names follow storage's case-sensitive rules.

```text
query       := SELECT select_list FROM identifier [WHERE predicate] [;] EOF
select_list := * | column_ref (, column_ref)*
predicate   := or_expr
or_expr     := and_expr (OR and_expr)*
and_expr    := not_expr (AND not_expr)*
not_expr    := NOT not_expr | (predicate) | comparison
comparison  := operand comparison_op operand
operand     := column_ref | signed_integer
column_ref  := identifier [. identifier]
comparison_op := = | <> | < | <= | > | >=
```

Rules:

- Comparisons bind before `NOT`, followed by `AND`, then `OR`. Parentheses override default precedence.
- Integer literals accept an optional sign and range from -2147483648 to 2147483647. Parse the complete signed value before checking its range so that the minimum integer is accepted.
- Reject `WHERE age`, `age < 20 < 30`, and unconsumed trailing SQL.
- `SELECT *` cannot be mixed with other selection items. `table.*` is unsupported.
- Unsupported syntax must fail explicitly rather than being ignored during execution.

Proposed AST nodes:

| Node | Contents |
| --- | --- |
| `IntLiteral` | `int value` |
| `Comparison` | `Expression left, ComparisonOp op, Expression right` |
| `And` / `Or` | `Expression left, Expression right` |
| `Not` | `Expression child` |
| `Star` | Allowed only as the sole SELECT item |

These nodes implement the existing `Expression` interface. Retain `ColumnRef`. `Query.from` must contain exactly one table, and `joins` must be empty. The Planner also validates these constraints for callers that construct ASTs directly.

## 5. Planner: Column Binding and Type Checking

The Planner processes a query in this order:

1. Validate that the query falls within the Version 1 scope.
2. Call `Catalog.getTable(name)` to retrieve column definitions and verify that every column is `INTEGER`.
3. Map column names to positions matching the indexes in `Row.values()`.
4. Bind WHERE column references, checking table qualifiers and unknown columns.
5. Verify that comparison operands are integers and AND / OR / NOT operands are predicates.
6. Bind SELECT columns; expand `*` into all columns in schema order.
7. Produce the logical plan.

Binding happens once; execution accesses rows directly by index. Represent bound integer expressions separately from bound predicates:

```text
BoundIntExpression
  BoundIntLiteral(value)
  BoundColumn(index)          // Version 1 validates all columns as INTEGER

BoundPredicate
  BoundComparison(left, op, right)
  BoundAnd(left, right)
  BoundOr(left, right)
  BoundNot(child)
```

Booleans are intermediate expression results. No BOOLEAN type needs to be added to storage's `DataType`.

Proposed logical nodes:

```text
LogicalScan(tableName)
LogicalFilter(child, boundPredicate)
LogicalProject(child, columnIndexes)
```

The example query produces this plan:

```text
LogicalProject([index of id])
└── LogicalFilter(age >= 18 AND score > 80)
    └── LogicalScan(students)
```

Omit Filter when WHERE is absent. Filter executes before Project because WHERE may reference columns that SELECT does not return. Version 1 performs no plan optimization.

## 6. Executor: Streaming Execution

`Executor.compile()` recursively converts logical nodes into implementations of the existing `Operator` interface.

### TableScanOperator

Obtain a `RowCursor` through `TableStore.scan(tableName)` and delegate `open()`, `next()`, and `close()`. Scan returns all columns in the schema.

### FilterOperator

Construct the operator with a child operator and a bound predicate. Its core behavior is:

```java
public Row next() {
    Row row;
    while ((row = child.next()) != null) {
        if (predicate.test(row)) {
            return row;
        }
    }
    return null;
}
```

- Return the original row, preserving column layout, duplicate rows, and input order.
- Evaluate AND / OR from left to right with short-circuiting; NOT negates its operand.
- Use direct integer comparisons or `Integer.compare`. Avoid subtraction-based comparisons, which can overflow.
- SQL NULL is outside this version's scope, so predicates produce only true or false.
- Filter holds only the current row and fixed expression state; it does not collect the complete result.

Proposed runtime interfaces:

```java
interface IntEvaluator {
    int evaluate(Row row);
}

interface RowPredicate {
    boolean test(Row row);
}
```

The Executor compiles bound expressions into these evaluators. Execution performs no catalog lookup by column name.

### ProjectOperator

Build an output `Row` from the bound column indexes, copying only references to selected values. Preserve selection order and repeated columns.

### Lifecycle and Memory

- Operators support one execution. They must be opened before reading, return null on repeated next calls after EOF, and provide idempotent close behavior.
- Wrapper operators close their children. Early termination and execution errors must also close the underlying cursor.
- If open partially succeeds and then fails, release any resources already opened. Currently, `Engine` calls `root.open()` outside its try block; implementation must cover this failure path while preserving the original exception.
- Scan / Filter / Project can stream rows, but the existing `Engine` collects all output in `QueryResult`. Total query result memory therefore still grows with output size.

## 7. Error Behavior

| Stage | Examples | Expected behavior |
| --- | --- | --- |
| Parse | Missing WHERE predicate, literal out of range, unsupported syntax | Report a parse error with a location or context |
| Plan | Unknown column, incorrect table qualifier, table containing STRING | Fail before scanning; identify the object and reason |
| Plan | Manually constructed AST using JOIN, aliases, or a non-predicate WHERE | Reject explicitly without ignoring fields |
| Execute / Storage | Invalid CSV integer, incorrect row width, I/O failure | Stop execution and close resources, retaining storage error context |

Introduce `SqlParseException` and `QueryPlanningException`; storage continues using `StorageException`. The CLI should display these expected errors and allow the next query to be entered. Never silently skip a malformed row.

## 8. Acceptance Criteria

Use an integer-only test table. The contents of `students.schema` are:

```text
id INTEGER
age INTEGER
score INTEGER
```

The contents of `students.csv` are:

```csv
id,age,score
1,17,90
2,18,85
3,20,80
4,21,95
```

| Query or scenario | Expected result |
| --- | --- |
| `SELECT id FROM students WHERE age >= 18 AND score > 80` | IDs 2 and 4 |
| `SELECT id FROM students WHERE age < 18 OR age >= 20 AND score > 90` | IDs 1 and 4; verifies AND precedence |
| `SELECT id FROM students WHERE NOT (age < 18 OR score <= 80)` | IDs 2 and 4 |
| `SELECT id FROM students WHERE age > score` | Empty result; verifies comparison between columns |
| `SELECT score, id FROM students WHERE id = 2` | One row: `[85, 2]` |
| `SELECT id, id FROM students WHERE id = 2` | One row: `[2, 2]` |
| `SELECT * FROM students` | All columns and rows in input order |
| `SELECT students.id FROM students WHERE students.age = 18` | ID 2 |

Also cover:

- Equality boundaries for all six comparison operators, negative values, and minimum/maximum integers; comparisons across integer extremes must not overflow.
- Empty tables, all rows matching, no rows matching, duplicate rows, constant comparisons, and filtering by columns absent from the output.
- Unknown columns, non-INTEGER tables, integer overflow, trailing SQL, and all explicitly excluded syntax.
- Planner rejection of invalid ASTs constructed directly.
- A counting test child operator to verify that Filter reads on demand rather than consuming all rows in advance; AND / OR short-circuit behavior.
- Resource cleanup on normal EOF, early close, next failure, and open failure.

Organize tests into parser, planner, expression evaluation/operator, and `Engine.execute(sql)` integration tests. Acceptance depends on end-to-end output and observable error and resource behavior.

## 9. Implementation Order and Team Interfaces

1. **Agree on contracts:** Confirm scope, expression nodes, type rules, and error behavior.
2. **Complete the execution path:** Construct ASTs directly, implement column binding and Scan → Filter → Project, and verify output.
3. **Connect SQL parsing:** Implement the tokenizer/parser to produce the same ASTs, covering precedence and invalid input.
4. **Complete integration:** Execute test SQL through Engine / CLI, verify error handling and resource cleanup, and run `mvn test`.

The Parser delivers `Query` / `Expression`. The Planner delivers a logical plan containing bound expressions. The Executor consumes that plan and `TableStore`. Once these interfaces are agreed upon, each component can be developed and tested independently.

Future JOIN support can reuse comparison and logical expressions, but column binding must expand to handle aliases, ambiguous column names, and column positions after a join. ORDER BY and GROUP BY require separate designs and do not affect acceptance of this version.
