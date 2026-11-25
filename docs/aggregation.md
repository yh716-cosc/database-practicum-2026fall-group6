# Aggregation and GROUP BY

Task 3. MiniDB supports global and grouped aggregates over one table or a two-table
JOIN, with `GROUP BY`, `HAVING`, and aggregate-aware `ORDER BY`.

## Syntax

```text
SELECT item [, item ...]
FROM table [alias] [[INNER | LEFT [OUTER] | RIGHT [OUTER]] JOIN table [alias] ON a.x = b.y]
[WHERE predicate]
[GROUP BY column [, column ...]]
[HAVING predicate]
[ORDER BY key [ASC | DESC] [, key ...]]
[LIMIT n]

item      := column | aggregate
key       := column | aggregate
aggregate := COUNT(*) | COUNT(column) | SUM(column) | AVG(column) | MIN(column) | MAX(column)
```

- Function names are case-insensitive. A name is a function only when an opening
  parenthesis follows it, so a column named `count` remains a column.
- Columns may be qualified (`e.points`) under the existing binding rules.
- `HAVING` accepts the usual comparisons, `IS [NOT] NULL`, `AND`/`OR`/`NOT`, and
  parentheses over grouped columns, aggregates, and integer/string literals.
- Clauses must appear in the order above. A misplaced `GROUP BY` or `HAVING` is a
  syntax error that names the expected order.

## Which queries aggregate

A query is grouped when it has `GROUP BY`, including one that selects only the
group keys. Without `GROUP BY`, an aggregate anywhere in SELECT, HAVING, or ORDER BY
makes the whole input one global group. `HAVING` without either is rejected.

In an aggregate query every column in SELECT, HAVING, and ORDER BY must be a
group key or appear inside an aggregate:

```sql
SELECT name, COUNT(*) FROM students;                 -- planning error
SELECT name, COUNT(*) FROM students GROUP BY name;   -- valid
```

`SELECT *` is rejected in aggregate queries. Repeating a `GROUP BY` key is allowed
and groups once.

## Stage order

```text
Scan or JOIN -> optional WHERE -> Aggregate -> optional HAVING
             -> optional Sort -> optional Limit -> Project
```

WHERE sees the scanned or joined input row, as before, and aggregates are rejected
there and in JOIN ON. HAVING, ORDER BY, and SELECT bind to the Aggregate output:
group keys in GROUP BY order, then one slot per distinct aggregate call. Aggregates
used only by HAVING or ORDER BY occupy hidden slots that the final projection drops.
Equivalent calls (same function and input column, such as `SUM(points)` and
`SUM(e.points)`) are computed once per group.

The optimizer rewrites below the Aggregate (constant folding, index scans for WHERE)
but never moves HAVING, sorts, or projections across it. The cost-based join chooser
looks through the Aggregate, because aggregate values do not depend on join row order.

## Result types and semantics

| Function | Accepted input | Result type | NULLs and empty input |
| --- | --- | --- | --- |
| `COUNT(*)` | all rows | BIGINT | Counts every row, including NULL-extended join rows. 0 on empty input. |
| `COUNT(column)` | INTEGER or STRING | BIGINT | Counts non-NULL values. 0 on empty or all-NULL input. |
| `SUM(column)` | INTEGER | BIGINT | Ignores NULLs. Typed BIGINT NULL when there are no values. |
| `AVG(column)` | INTEGER | DECIMAL | Ignores NULLs. Typed DECIMAL NULL when there are no values. |
| `MIN` / `MAX` | INTEGER or STRING | input type | Ignore NULLs. Numeric order, or case-sensitive string order (as ORDER BY). Typed NULL when there are no values. |

- **BIGINT and DECIMAL are query-result types only.** They are Java `Long` and
  `BigDecimal` values. Schema files and table writes reject them; stored columns remain
  INTEGER or STRING.
- **AVG** divides the exact sum by the non-NULL count once and rounds to 6 fractional
  digits with HALF_EVEN rounding. It prints in plain notation, for example `7.600000`.
- **Overflow.** Counts and sums are exact. A sum is kept in a `long` and widens to
  `BigInteger` if needed, so AVG never overflows. If a SUM result does not fit in
  BIGINT, the query fails with `QueryExecutionException` (`SUM(x) overflows BIGINT`).
  The CLI prints that message and accepts the next query.
- **Empty input.** A global aggregate emits exactly one row before HAVING, even when
  there is no input. A grouped aggregate emits no rows.
- **Grouping keys.** Typed NULL keys form one group. NULL and the empty string are
  different keys.
- **Order.** Group order is unspecified without ORDER BY. In the current implementation
  it is first-seen input order. ORDER BY uses the existing policy: NULLs last for ASC,
  first for DESC.
- **HAVING comparisons.** INTEGER, BIGINT, and DECIMAL compare with each other exactly,
  so `HAVING COUNT(*) >= 2` and `HAVING AVG(score) > 80` work with integer literals.
  STRING rules are unchanged (`=` and `<>` only). Three-valued logic applies: a
  comparison with NULL is UNKNOWN, which fails HAVING and stays UNKNOWN under NOT.
- **Labels.** Group keys keep their column name. Aggregates display as
  `FUNCTION(argument)` with an uppercase function name and the argument as written,
  for example `COUNT(*)` or `AVG(e.points)`. SELECT order and duplicates are kept.

## Execution and memory

`AggregateOperator` is a blocking hash aggregate built on `UnaryOperator`. Its first
`next()` drains the child and keeps one accumulator per aggregate for each group in a
`LinkedHashMap` keyed by the group's typed values. It does not retain input rows, so
peak memory grows with the number of groups. A global aggregate has one state. Results
are emitted on later `next()` calls. State is released on close, EOF, or failure. A
child read failure or overflow closes the child and propagates. LIMIT above the
aggregate stops output early but cannot avoid reading the whole input.

## Not supported

Arithmetic or scalar functions, nested aggregates, `DISTINCT` aggregates, GROUP BY
ordinals or expressions, select aliases, standalone NULL literals, decimal literals,
and English-to-SQL aggregation translation.

## Reproducible demo

The fixture is in `local-test-data/aggregation`. It contains `events(dept STRING,
points INTEGER)`, with typed NULLs, and `depts(dept STRING, floor INTEGER)`:

```text
events: (A,10) (A,11) (A,NULL) (B,5) (B,NULL) (C,NULL) (NULL,4) (NULL,8)
depts:  (A,1) (A,2) (B,3) (D,4)
```

```sh
mvn -q package
java -jar target/minidb-0.1.0.jar local-test-data/aggregation
```

Enter each query on one line. Expected output:

```text
minidb> SELECT COUNT(*), COUNT(points), SUM(points), AVG(points), MIN(points), MAX(points) FROM events;
COUNT(*) | COUNT(points) | SUM(points) | AVG(points) | MIN(points) | MAX(points)
---------+---------------+-------------+-------------+-------------+------------
8 | 5 | 38 | 7.600000 | 4 | 11
(1 rows)
minidb> SELECT dept, COUNT(*), COUNT(points), SUM(points) FROM events GROUP BY dept ORDER BY dept;
dept | COUNT(*) | COUNT(points) | SUM(points)
-----+----------+---------------+------------
A | 3 | 2 | 21
B | 2 | 1 | 5
C | 1 | 0 | NULL
NULL | 2 | 2 | 12
(4 rows)
minidb> SELECT dept, COUNT(*) FROM events WHERE points >= 5 GROUP BY dept HAVING COUNT(*) >= 2;
dept | COUNT(*)
-----+---------
A | 2
(1 rows)
minidb> SELECT dept FROM events GROUP BY dept HAVING COUNT(points) > 0 ORDER BY SUM(points) DESC LIMIT 2;
dept
----
A
NULL
(2 rows)
minidb> SELECT e.dept, COUNT(*), COUNT(d.floor), MAX(d.floor) FROM events e LEFT JOIN depts d ON e.dept = d.dept GROUP BY e.dept ORDER BY e.dept;
dept | COUNT(*) | COUNT(d.floor) | MAX(d.floor)
-----+----------+----------------+-------------
A | 6 | 6 | 2
B | 2 | 2 | 3
C | 1 | 0 | NULL
NULL | 2 | 0 | NULL
(4 rows)
minidb> SELECT d.dept, COUNT(*), AVG(e.points) FROM depts d JOIN events e ON d.dept = e.dept GROUP BY d.dept HAVING AVG(e.points) > 6;
dept | COUNT(*) | AVG(e.points)
-----+----------+--------------
A | 6 | 10.500000
(1 rows)
minidb> SELECT dept, COUNT(*) FROM events;
Column dept in SELECT must appear in GROUP BY or be used in an aggregate function
minidb> SELECT SUM(*) FROM events;
line 1, column 12: SUM(*) is not supported; only COUNT accepts *
minidb> SELECT COUNT(*) FROM events WHERE dept = 'Z';
COUNT(*)
--------
0
(1 rows)
```

## Tests

- `sql/AggregateParserTest`: AST shapes, contextual names, clause order, malformed calls.
- `plan/AggregatePlannerTest`: stage order, output layout, interning, hidden slots,
  JOIN binding, rejection messages, and the optimizer boundary.
- `exec/AggregateOperatorTest`: empty input, NULL keys, MIN/MAX order, 64-bit sums,
  AVG rounding, overflow, failed reads, early close, LIMIT above aggregation.
- `AggregationTest`: the handoff acceptance cases end to end, including LEFT JOIN,
  index scans under WHERE, result-only type rejection by storage, and CLI recovery.

## Team decisions to confirm (Task 5)

The BIGINT/DECIMAL result types, AVG scale and rounding, and overflow error are this
task's proposals. They do not change the storage format. If the team adopts a different
numeric or NULL representation, the affected code is `DataType`,
`AggregateOperator`, `NumericValues`, and the formatting in `QueryResult`.
