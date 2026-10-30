# MiniDB

A Java 17 database project supporting single-table and two-table INNER/LEFT/RIGHT JOIN queries over
CSV files with explicit schemas. SQL parsing, planning, scan, filtering, projection,
and CLI execution are implemented. The CLI is read-only.

## Quick start

Install JDK 17 or newer and Maven, then run these commands from the repository root:

```sh
java -version
mvn -version
mvn -q package
java -jar target/minidb-0.1.0.jar local-test-data
```

`package` runs the tests and produces a JAR containing runtime dependencies.
The shared sample directory is tracked in Git; it contains 11 synthetic rows.
Enter one complete SQL statement per line at `minidb>`:

```sql
SELECT * FROM students;
SELECT name FROM students WHERE age >= 18 AND score >= 85;
SELECT id FROM students WHERE name = 'O''Brien';
SELECT id FROM students WHERE name = '';
```

The CLI also accepts the deterministic English equivalents below:

```text
show all from students
show name, age from students
show name from students where age is at least 18 and score is at least 85
show id from students where name equals 'O''Brien'
show id from students where age is null or name is not null
show s.name, e.course from students as s inner join enrollments as e
  on s.id equals e.student_id ordered by e.course descending limited to 10 rows
```

English commands support `equals`, `is not equal to`, `is less than`,
`is at most`, `is greater than`, `is at least`, `and`, `or`, `not`, parentheses,
and `is null` / `is not null`. They also support `inner join`, `left join`,
`right join`, optional `as` aliases, `order by`/`sort by`/`ordered by`, ascending
or descending keys, and `limit`/`return at most`/`limited to` row counts. They
are translated into SQL and then use the same parser, planner, and executor as
direct SQL input. The English grammar is deliberately strict; unsupported
wording is rejected rather than guessed.

Expected results: 11 rows; 6 names (Alice, Carol, O'Brien, alice, Max, Alice);
ID 4; and ID 6, respectively. The final semicolon is optional.
Type `quit` or `exit` to leave. Multiline statements and multiple statements on
one line are not supported by the CLI.

Output displays column names and cell values, for example:

```text
id
--
Output displays cell values, for example:

```text
4
(1 rows)
```

Results include column names; empty strings display as `""` and typed NULLs as `NULL`.
Literal STRING `NULL` and strings starting with a single quote are quoted with doubled
quote escaping to distinguish them. Other strings retain raw-value display with
backslash, newline, tab, and pipe escaping. See [shared sample queries](local-test-data/README.md)
for additional inputs and expected results.

## Supported SQL

- One table or a two-table INNER/LEFT/RIGHT equijoin, `SELECT *` or an ordered column list, including repeated columns.
- `FROM a [AS x] [INNER | LEFT [OUTER] | RIGHT [OUTER]] JOIN b [AS y] ON x.id = y.id`; implicit table aliases also work.
- ON must be one equality between matching-type columns from opposite sides.
- Unqualified columns must be unambiguous. An alias replaces the original table qualifier.
- JOIN rows and `SELECT *` contain left columns followed by right columns; output labels retain original column names.
- LEFT/RIGHT JOIN null-extend unmatched rows. WHERE/ON use SQL three-valued logic.
- `column IS NULL` and `column IS NOT NULL`; NULL sorts last in ASC and first in DESC, and prints as `NULL`.
- Optional WHERE with integer/string literals and column-to-column comparisons.
- INTEGER comparisons: `=`, `<>`, `<`, `<=`, `>`, `>=`.
- STRING comparisons: `=` and `<>`, with exact case-sensitive matching.
- `NOT`, `AND`, `OR`, and parentheses. Precedence: comparison, NOT, AND, OR.
- ORDER BY named INTEGER/STRING columns, multiple keys, ASC (default) or DESC.
- LIMIT with a non-negative 32-bit integer; no OFFSET.
- `column IS NULL` / `IS NOT NULL`; NULL comparisons use SQL three-valued logic.
- Bare column names or actual table qualifiers, such as `students.age`.
- Case-insensitive SQL keywords; case-sensitive table and column names.
- Signed 32-bit integers; single-quoted strings with doubled quote escaping.

No implicit type conversion: `age = '18'` is a planning error.
FULL/non-equality/multiway joins, column aliases, comma-separated tables, GROUP BY, aggregates, DISTINCT, OFFSET,
LIKE, standalone NULL literals, arithmetic, comments, subqueries, and SQL writes are unsupported.
NULL values can come from outer joins or versioned nullable CSV storage.
`table.*` and mixing `*` with other selected columns are also unsupported.

## Use your own tables

Supply a directory containing paired `<table>.schema` and `<table>.csv` files.
For example, `data/students.schema`:

```text
id INTEGER
name STRING
age INTEGER
```

And `data/students.csv`:

```csv
id,name,age
1,Alice,20
2,"Smith, Bob",17
```

Save as UTF-8 without a BOM. The CSV header must match schema names and order
exactly. INTEGER and STRING are the supported schema types. Empty string fields
are valid; empty integer fields are errors. A header-only CSV is an empty table.
Then run:

```sh
java -jar target/minidb-0.1.0.jar data
```

Prepare the files before starting queries; there is no SQL CREATE TABLE or INSERT.
See [storage formats and API](docs/storage.md) for quoting and validation rules.
Existing unmarked tables preserve their original field meanings. New tables written
through storage use a versioned NULL encoding: `\N` is typed NULL, and a literal
leading backslash is doubled. Empty STRINGs and literal `NULL` remain distinct.
Storage also exposes lazy `statistics(table)` and forced-refresh `analyze(table)`
operations with exact row/NULL counts and bounded-memory distinct counts. See
[statistics and external edits](docs/storage.md#table-statistics-and-external-edits)
for cache invalidation rules, including timestamp-preserving external edits.

## Architecture and status

```text
SQL -> Parser -> AST -> Planner + Catalog -> Logical plan
                                                |
                                          Executor.compile
                                                |
                                    Scan -> Filter -> Project
                                                |
                                           QueryResult
```

| Component | Implemented responsibility |
| --- | --- |
| Parser | Tokenization, SELECT AST, precedence, syntax diagnostics |
| Planner | Column binding, type checks, star expansion, logical plans |
| Optimizer | Fold constants, simplify predicates, merge consecutive projections |
| Executor | Convert plans into fresh operator trees without reading rows |
| Operators | Scan, filtering, stable in-memory sorting, streaming limit, projection, cleanup |
| Storage | Schema/catalog lookup, CSV cursors, new-table writes via Java API |
| Engine / CLI | Execute SQL, collect results, print rows, accept next query |

`Executor.compile()` creates Java operator objects; it does not invoke javac or
produce bytecode. Engine opens the root, pulls rows, and closes it.

## Errors and current limits

- Syntax and planning errors print a message and return to the prompt.
- Missing files, bad headers, invalid CSV values, and I/O errors raise
  `StorageException`; the CLI displays the error and accepts the next query.
- Scan/filter/project stream rows, but QueryResult collects all output in memory.
- Parser nesting is limited to 128 combined parentheses/NOT levels. Planner
  predicate-tree depth is limited to 256, including long flat AND/OR chains.
- Output includes column headers. No cost-based optimizer, indexes, or transactions.

If `java` or `mvn` is not recognized, check JDK/Maven installation and PATH.
If the JAR is missing, run `mvn -q package`. If a table is missing, check the
supplied directory and the case-sensitive filenames. After source changes,
rebuild the JAR before manually testing.

## Tests

```sh
mvn -q test
mvn -q "-Dtest=PlannerTest,ExecutorTest,EngineTest,CliTest" test
```

The suite covers parsing,
planning, operator lifecycle and cleanup, storage, full SQL results, and CLI
recovery after syntax, planning, and storage errors. Tests create their own fixtures; shared
samples are for manual verification.

For a separate storage API demonstration (use a fresh output directory each time):

```sh
java -cp target/minidb-0.1.0.jar minidb.storage.StorageDemo target/storage-demo
```

## Project layout and documentation

```text
src/main/java/minidb/
  Main.java, Engine.java  entry point and query pipeline
  cli/                   interactive command line
  sql/                   tokenizer, parser, AST
  catalog/, storage/     schemas and CSV storage
  types/                 INTEGER / STRING values
  plan/                  bound expressions and logical plans
  exec/                  executor, operators, results
src/test/java/minidb/     automated tests
local-test-data/         shared manual-test table and expected results
```

- [Project updates](CHANGELOG.md)
- [Parser grammar and API](docs/parser.md)
- [Planner and executor](docs/planner.md)
- [ORDER BY and LIMIT implementation](docs/sort-limit.md)
- [Filtering architecture and contracts](docs/query-filtering.md)
- [Storage](docs/storage.md)
- [Two-table JOIN and implementation reference](docs/join.md)

## Next work

Consider streaming
result output for large queries. Design JOIN or aggregation according to
Improve column headers and CLI handling of storage errors; consider streaming
result output for large queries. Design JOIN, sorting, or aggregation according to
course requirements before extending the current subset.

## Group

- [Group notes](https://docs.google.com/document/d/110spyKtpgsiMZG_rsPnJlrbhwEerZRns3I_K3ArTrzk/edit?usp=sharing)
- [Slack channel](https://app.slack.com/client/T0C1A7U2VJM/C0C1A7UQFFB)
