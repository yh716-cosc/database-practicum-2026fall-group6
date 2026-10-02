# Query Filtering — Version 1 Design

Status: Design draft with Java skeletons; query execution is not implemented. The conceptual names below describe responsibilities; the compact file layout in Section 10 maps them to the current skeletons.

## 1. Goals and Scope

Implement the complete single-table `SELECT / FROM / WHERE` pipeline: parse SQL, validate columns and types, scan data, filter rows, and return selected columns.

Version 1 supports signed 32-bit integers and strings, matching the existing storage types. This document calls the integer type `INT`; code and `.schema` files retain `DataType.INTEGER` / `INTEGER`, with Java `Integer` values. Strings use `DataType.STRING` / `STRING`, with Java `String` values.

Supported features:

- One base table, without table or column aliases.
- `SELECT *` or an explicit column list, preserving selection order and allowing repeated columns.
- An optional `WHERE` clause; omitting it returns all rows.
- Integer and string literals, column references, and comparisons between columns of the same type.
- Integer comparison operators: `=`, `<>`, `<`, `<=`, `>`, `>=`; string comparison operators: `=`, `<>`.
- Boolean operators: `AND`, `OR`, `NOT`, and parentheses for grouping predicates.
- Unqualified column names and names qualified by the actual table name, such as `age` and `students.age`.

Deferred features: string ordering comparisons, LIKE, SQL `NULL`, arithmetic expressions, type conversion, JOIN, multiple FROM tables, ORDER BY, GROUP BY, aggregation, HAVING, DISTINCT, LIMIT, subqueries, indexes, and filter pushdown.

Tables may contain any mixture of `INTEGER` and `STRING` columns. Both types can be selected and filtered. Comparisons require matching operand types; Version 1 performs no implicit conversion between strings and integers.

## 2. Existing Code

| Component | Current state | Version 1 changes |
| --- | --- | --- |
| `Engine` | Connects parse → plan → compile → execute | Review error paths and resource cleanup |
| `SqlParser` | Not implemented | Implement the supported SQL subset |
| `Query` | Contains select, from, joins, and where | Retain structure; null where means no filter |
| `Expression` / `ColumnRef` | Expression interface and column references exist | Add integer and string literals, comparison, and logical expressions |
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
- Storage converts CSV fields into typed rows according to the schema. INTEGER fields become integers; STRING fields preserve their text, including spaces, empty strings, and leading zeros. Invalid integers, overflow, or malformed records fail the scan.
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
operand     := column_ref | signed_integer | string_literal
column_ref  := identifier [. identifier]
comparison_op := = | <> | < | <= | > | >=
```

Rules:

- Comparisons bind before `NOT`, followed by `AND`, then `OR`. Parentheses override default precedence.
- Integer literals accept an optional sign and range from -2147483648 to 2147483647. Parse the complete signed value before checking its range so that the minimum integer is accepted.
- String literals use single quotes. Two consecutive single quotes inside a literal represent one quote, as in `'O''Brien'`. Support empty strings (`''`) and Unicode text; preserve case and whitespace. Backslashes have no escape semantics. Reject unterminated literals; double quotes are not string delimiters in this subset.
- SQL string quoting is separate from CSV quoting. The parser decodes SQL literals; storage handles CSV escaping. Neither layer applies the other layer's quoting rules.
- Reject `WHERE age`, `age < 20 < 30`, and unconsumed trailing SQL.
- `SELECT *` cannot be mixed with other selection items. `table.*` is unsupported.
- Unsupported syntax must fail explicitly rather than being ignored during execution.

Proposed AST nodes:

| Node | Contents |
| --- | --- |
| `IntLiteral` | `int value` |
| `StringLiteral` | `String value` containing decoded, non-null text |
| `Comparison` | `Expression left, ComparisonOp op, Expression right` |
| `And` / `Or` | `Expression left, Expression right` |
| `Not` | `Expression child` |
| `Star` | Allowed only as the sole SELECT item |

These nodes implement the existing `Expression` interface. Retain `ColumnRef`. `Query.from` must contain exactly one table, and `joins` must be empty. The Planner also validates these constraints for callers that construct ASTs directly.

## 5. Planner: Column Binding and Type Checking

The Planner processes a query in this order:

1. Validate that the query falls within the Version 1 scope.
2. Call `Catalog.getTable(name)` to retrieve column definitions and verify that each column is `INTEGER` or `STRING`.
3. Map column names to positions matching the indexes in `Row.values()`.
4. Bind WHERE column references, checking table qualifiers and unknown columns.
5. Verify that comparison operands have the same type, that the operator supports that type, and that AND / OR / NOT operands are predicates. Validate the entire expression, including branches that may be skipped at runtime.
6. Bind SELECT columns; expand `*` into all columns in schema order.
7. Produce the logical plan.

Binding happens once; execution accesses rows directly by index. Represent typed scalar expressions separately from bound predicates:

```text
BoundValueExpression         // Exposes its DataType
  BoundLiteral(value)        // Existing Value: INTEGER/Integer or STRING/String
  BoundColumn(index, type)   // Type comes from the catalog

BoundPredicate
  BoundComparison(left, op, right)
  BoundAnd(left, right)
  BoundOr(left, right)
  BoundNot(child)
```

Booleans are intermediate expression results. No BOOLEAN type needs to be added to storage's `DataType`.

Comparison rules:

| Operand types | Supported operators | Semantics |
| --- | --- | --- |
| INTEGER / INTEGER | `=`, `<>`, `<`, `<=`, `>`, `>=` | Signed numeric comparison |
| STRING / STRING | `=`, `<>` | Exact, case-sensitive Java `String.equals` equality; no trimming, locale rules, or Unicode normalization |
| INTEGER / STRING or STRING / INTEGER | None | Planning error; no implicit conversion |

For example, `age = '18'` and `name > 'Alice'` fail during planning. `name = ''` is valid. The string `'NULL'` is ordinary text; unquoted SQL NULL is unsupported.

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
- Compare strings with `String.equals`, never Java reference equality (`==`). Negate equality for `<>`; preserve whitespace and case.
- SQL NULL is outside this version's scope, so predicates produce only true or false.
- Filter holds only the current row and fixed expression state; it does not collect the complete result.

Proposed runtime interfaces:

```java
interface ValueEvaluator {
    Value evaluate(Row row);
}

interface RowPredicate {
    boolean test(Row row);
}
```

The Executor compiles bound expressions into these evaluators and selects integer or string comparison logic using the types checked during planning. Literals return a fixed typed Value; column evaluators return the Value at the bound index. Execution performs no catalog lookup by column name or string-to-integer coercion.

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
| Parse | Missing WHERE predicate, literal out of range, unterminated string, unsupported syntax | Report a parse error with a location or context |
| Plan | Unknown column, incorrect table qualifier, mismatched operand types, unsupported operator for a type | Fail before scanning; identify the object and reason |
| Plan | Manually constructed AST using JOIN, aliases, or a non-predicate WHERE | Reject explicitly without ignoring fields |
| Execute / Storage | Invalid CSV integer, incorrect row width, I/O failure | Stop execution and close resources, retaining storage error context |

Introduce `SqlParseException` and `QueryPlanningException`; storage continues using `StorageException`. The CLI should display these expected errors and allow the next query to be entered. Never silently skip a malformed row.

## 8. Acceptance Criteria

Use a table containing both supported types. The contents of `students.schema` are:

```text
id INTEGER
age INTEGER
score INTEGER
name STRING
nickname STRING
```

The contents of `students.csv` are:

```csv
id,age,score,name,nickname
1,17,90,Alice,Alice
2,18,85,Bob,Bobby
3,20,80,O'Brien,OB
4,21,95,,
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
| `SELECT name FROM students WHERE age >= 18 AND name <> ''` | Names `Bob` and `O'Brien` |
| `SELECT id FROM students WHERE name = 'O''Brien'` | ID 3; verifies SQL quote escaping |
| `SELECT id FROM students WHERE name = nickname` | IDs 1 and 4; verifies string column comparison |
| `SELECT id FROM students WHERE name = ''` | ID 4; empty strings are valid values |
| `SELECT id FROM students WHERE name = 'alice'` | Empty result; equality is case-sensitive |
| `SELECT id FROM students WHERE age = '18'` | Planning error: INTEGER / STRING mismatch |
| `SELECT id FROM students WHERE name > 'Alice'` | Planning error: ordering comparison is unsupported for STRING |

Also cover:

- Equality boundaries for all six comparison operators, negative values, and minimum/maximum integers; comparisons across integer extremes must not overflow.
- Empty tables, all rows matching, no rows matching, duplicate rows, constant comparisons, and filtering by columns absent from the output.
- String-only and mixed-type tables; exact equality and inequality for Unicode, leading/trailing spaces, leading zeros, empty strings, and the ordinary text `NULL`.
- SQL strings containing commas, parentheses, or keywords; doubled quote decoding and unterminated literal errors. Include CSV values containing quotes or commas to verify that CSV and SQL escaping remain independent.
- Unknown columns, mismatched comparison types (including column-to-column comparisons), unsupported string operators, integer overflow, trailing SQL, and all explicitly excluded syntax. Type errors must fail even for empty tables or branches skipped by short-circuit evaluation.
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

## 10. Compact Skeleton Layout

Related expression and plan nodes are nested records to keep the initial file count small. Paths below are relative to `src/main/java/minidb/`.

| File | Skeleton contents |
| --- | --- |
| `sql/ast/Expression.java` | IntLiteral, StringLiteral, ComparisonOp, Comparison, And, Or, Not, and Star |
| `sql/ast/ColumnRef.java` | Existing column reference; retained as a separate record |
| `plan/LogicalOperator.java` | Scan, Filter, and Project records |
| `plan/BoundExpression.java` | Scalar and Predicate interfaces; Column, Literal, Comparison, And, Or, and Not records |
| `exec/ExpressionEvaluator.java` | Implemented evaluate(Scalar, Row) and test(Predicate, Row), with unit tests |
| `exec/TableScanOperator.java` | Implemented single-use streaming scan, EOF cleanup, and failure cleanup, with unit and CSV integration tests |
| `exec/FilterOperator.java` | Implemented streaming filter with lifecycle, failure cleanup, and unit/integration tests |
| `exec/ProjectOperator.java` | Constructor accepting a child and column indexes; lifecycle stubs |

For this compact version, ExpressionEvaluator evaluates bound nodes directly instead of introducing separate ValueEvaluator, RowPredicate, and ExpressionCompiler files. The typing and comparison rules above still apply. Evaluation supports typed literals, bound columns, comparisons, and short-circuit boolean logic. The planner remains responsible for validating the entire expression before execution; runtime evaluation also rejects encountered mismatched comparison types and unsupported string operators. It assumes valid non-null Value payloads and correctly bound row layouts. Project lifecycle methods still throw UnsupportedOperationException until implemented.

TableScanOperator acquires its cursor on open and reads one row per next call. EOF closes the cursor; repeated next calls at EOF return null until explicit close. Explicit close is idempotent and prohibits further reads or reopening, including when called before open. Acquisition, open, and read failures leave the operator closed. Cleanup failures are suppressed on the original open/read exception. A close failure is reported once without retrying cleanup.

FilterOperator owns its child and evaluates one row at a time until it finds a match. It returns the original matching row, preserving column layout, order, and duplicates. EOF closes the child; repeated next calls then return null until explicit close. Closing before open closes the owned child without opening it. Child open/read failures and predicate evaluation failures close the child and preserve the original exception, with any cleanup error suppressed. Cleanup is attempted once, including when close fails.

SqlParser, Planner, and Executor remain unimplemented. Their future integration points are Expression nodes, LogicalOperator nodes with BoundExpression predicates, and the three physical operators, respectively.
