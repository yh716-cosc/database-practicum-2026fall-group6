# SQL parser

This component turns one SQL statement into a `Query` tree. It checks syntax only.
Table lookup, column binding, operand types, and row evaluation belong to the
planner and executor. The parser does not read `.csv` or `.schema` files.

Version 1 accepts one `SELECT` over one unaliased table, with an optional `WHERE`
and an optional final semicolon. The statement must consume the whole string.
`docs/query-filtering.md` describes the larger single-table pipeline; this guide
covers the parser that is implemented now.

## Call the parser

```java
Parser parser = new SqlParser();
Query query = (Query) parser.parse("""
        SELECT id, name
        FROM students
        WHERE age >= 18 AND name <> '';
        """);
```

Imports are `minidb.sql.Parser`, `minidb.sql.SqlParser`, and `minidb.sql.ast.*`.
Each `parse` call scans its own input, so a failed call does not affect the next
one. A null, empty, or whitespace-only string throws `SqlParseException`.

The example above has this shape:

```text
Query
  select: ColumnRef(null, "id"), ColumnRef(null, "name")
  from:   TableRef("students", null)
  joins:  empty
  where:  And
            Comparison(ColumnRef(null, "age"), GE, IntLiteral(18))
            Comparison(ColumnRef(null, "name"), NE, StringLiteral(""))
```

Select-item aliases and the table alias are null. `where` is null when the
query has no filter. The select, from, and join lists are immutable snapshots.

## Grammar

```text
query         := SELECT select_list FROM identifier [WHERE predicate] [;] EOF
select_list   := * | column_ref (, column_ref)*
predicate     := or_expr
or_expr       := and_expr (OR and_expr)*
and_expr      := not_expr (AND not_expr)*
not_expr      := NOT not_expr | '(' predicate ')' | comparison
comparison    := operand (comparison_op operand | IS [NOT] NULL)
operand       := column_ref | signed_integer | string_literal
column_ref    := identifier ['.' identifier]
comparison_op := '=' | '<>' | '<' | '<=' | '>' | '>='
```

Keywords are case-insensitive. Comparison binds before `NOT`, then `AND`, then
`OR`. `AND` and `OR` associate to the left. Parentheses override that order.

`SELECT *` is legal only as the sole select item. The select list keeps order
and repeated columns. A column is a bare name or `table.column`; identifier case
is preserved. Unqualified columns use a null table in `ColumnRef`.

## Literals, names, and whitespace

- Identifiers match storage's `[A-Za-z_][A-Za-z0-9_]*`. `SELECT`, `FROM`,
  `WHERE`, `AND`, `OR`, and `NOT` are reserved. Unquoted `NULL` is accepted only
  in `IS [NOT] NULL`; `IS` is contextual and can still be a column name.
  A name such as `order` or `join` can still be a column or table; `ORDER BY`
  and `JOIN` cannot.
- Integers are signed 32-bit values, including `-2147483648`. A sign must sit
  directly against the digits. Leading zeros are accepted and use the numeric
  value, so `007` is 7. Overflow is a parse error.
- Strings use single quotes. `''` inside a literal is one quote, as in
  `'O''Brien'`. Empty strings and Unicode text are kept. A backslash is an
  ordinary character. Double quotes are not string delimiters.
- `parse` accepts ordinary whitespace and line breaks, including tabs. The CLI
  still reads one trimmed line and does not assemble a multiline statement.
- The text `'NULL'` is a string. `age = '18'` and `name > 'Alice'` parse.
  Whether those comparisons are legal for the table's types is a planning
  decision. Unknown columns and qualifiers stay in the tree.

## AST nodes

Statement and table nodes were already in the repository. Expression nodes live
inside `sql/ast/Expression.java`:

| SQL | Java |
| --- | --- |
| `18` | `Expression.IntLiteral` |
| `'O''Brien'` | `Expression.StringLiteral` with decoded text |
| `= <> < <= > >=` | `ComparisonOp.EQ, NE, LT, LE, GT, GE` |
| comparison | `Expression.Comparison` |
| `AND` / `OR` / `NOT` | `Expression.And`, `Or`, `Not` |
| `operand IS [NOT] NULL` | `Expression.IsNull(operand, negated)` |
| lone `*` | `Expression.Star` as the only `SelectItem` |
| `students.age` / `age` | existing `ColumnRef` |

`ColumnRef` remains its own record. The parser does not build `Join` values or
bound expressions. `ComparisonOp` is the enum shared with the planner when it
binds columns to indexes.

## What this version rejects

Unsupported syntax fails with `SqlParseException`. It is not ignored. The
message describes the error and includes a 1-based line and column. The exception's
`offset()` accessor provides the 0-based position in the original input; that
offset is not printed in the message. A tab counts as one column. Line breaks are `\n`,
`\r\n`, or a bare `\r`.

Rejected forms include JOIN, aliases, more than one table, `table.*`, `*` mixed
with other select items, `ORDER BY`, `GROUP BY`, `HAVING`, `DISTINCT`,
aggregates, `LIMIT`, `LIKE`, standalone NULL literals, quoted identifiers, comments,
arithmetic, subqueries, updates, chained comparisons such as `age < 20 < 30`,
and a second statement after the semicolon.

Parentheses and `NOT` share a limit of 128 simultaneously nested levels.
For example, `NOT (a = 1)` uses two levels. The next `NOT` or opening parenthesis
past the limit produces a positioned `SqlParseException`, so deeply nested input
does not terminate the CLI. Separate sibling predicates do not accumulate levels.

## Team integration

- Engine.execute parses, plans, compiles an operator tree, and collects query results.
- Planner validates the single-table subset, binds names, checks types, and expands
  star in schema order. String ordering can parse but fails planning.
- Operators evaluate BoundExpression nodes produced by Planner. Both layers share
  ComparisonOp: EQ, NE, LT, LE, GT, and GE.
- The CLI prints SqlParseException and PlanningException and reads the next line.
  StorageException still propagates. Type quit or exit to leave.
- Parser nesting limits and Planner tree-depth limits are separate; see
  [planner.md](planner.md) for the latter.

## Run and verify

```bash
mvn -q "-Dtest=SqlParserTest,SqlTokenizerTest,ExpressionAstTest,CliTest" test
mvn -q test
mvn -q package
```

Parser tests do not need CSV fixtures. They assert tree structure, including
precedence and parentheses, and they check error positions. Tokenizer tests
cover quoting, keyword boundaries, signed bounds, and whitespace. `CliTest`
sends a syntax error, then a statement that returns actual query results, then
another syntax error. It also verifies recovery after a planning error.
