# MiniDB

A Java 17 database project supporting single-table and two-table INNER/LEFT/RIGHT JOIN queries over
CSV files with explicit schemas. SQL parsing, planning, scan, filtering, projection,
and CLI execution are implemented. The CLI never modifies table data; its only writes
are `CREATE INDEX` and `DROP INDEX`, which create and delete index files.

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

## Optional LLM English translation

For more natural English, the CLI can use a local Ollama model to produce a structured
read-only query request. The model never supplies executable SQL: local code
validates the request and generates SQL before passing it to the normal parser,
planner, optimizer, and executor. Only table/column metadata and types are sent
to the model; CSV rows are not sent.

Install Ollama, download a model, and enable the provider before starting the CLI:

```sh
ollama pull qwen3:4b
export MINIDB_LLM_ENABLED=true
java -jar target/minidb-0.1.0.jar local-test-data
```

The model name defaults to `qwen3:4b` and can be overridden with `OLLAMA_MODEL`.
The endpoint defaults to `http://localhost:11434/api/chat` and can be overridden
with `OLLAMA_URL`. Direct SQL and the existing deterministic `show ...` syntax
remain available. If LLM translation is enabled but Ollama is unavailable, the
model request, response decoding, or local validation fails, the CLI reports an error and does not
execute a query.

Example natural-language input:

```text
Show me the names of students who are at least 18 and have scores above 85.
```

The LLM must map this to a structured request such as `name`, `students`,
`age >= 18`, and `score > 85`; SQL generation and database validation remain
local.

The full HTTP/tool-call/validation/generation path can be tested offline with
the local fake-endpoint test:

```sh
mvn -q "-Dtest=OllamaQueryTranslatorTest" test
```

This simulates an Ollama response and does not require Ollama or a model.

Expected results: 11 rows; 6 names (Alice, Carol, O'Brien, alice, Max, Alice);
ID 4; and ID 6, respectively. The final semicolon is optional.
Type `quit` or `exit` to leave. Type `\timing` (or `\timing on` / `\timing off`)
to print `Time: <ms> ms` after each successful statement. Multiline statements and multiple statements on
one line are not supported by the CLI.

Inspect and refresh table statistics at the same prompt:

```text
.stats students
.analyze students
```

`.stats` collects statistics on first use or after detected file changes, otherwise
reusing the current session's cache. `.analyze` always scans every record; use it
after external edits that preserve file size and timestamps. Both commands print
row count, CSV bytes, collection time, and each column's NULL and distinct non-null
counts, labeled `exact` or `estimated`. Commands are case-insensitive; table names
remain case-sensitive. A final semicolon is optional. Errors return to the prompt.

Output displays column names and cell values, for example:

```text
id
--
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
- `CREATE INDEX name ON table (column)` on one INTEGER column `DROP INDEX name`,
  and `SHOW INDEXES`; see [indexes](docs/indexing.md).

No implicit type conversion: `age = '18'` is a planning error.
FULL/non-equality/multiway joins, column aliases, comma-separated tables, GROUP BY, aggregates, DISTINCT, OFFSET,
LIKE, standalone NULL literals, arithmetic, comments, subqueries, and SQL writes other than
CREATE INDEX and DROP INDEX are unsupported.
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
operations with exact row/NULL counts and bounded-memory distinct counts, plus
`cachedStatistics(table)` to inspect a current cache entry without scanning data.
These APIs are also available on `Engine`, sharing its storage cache. See
[statistics and external edits](docs/storage.md#table-statistics-and-external-edits)
for cache invalidation rules, including timestamp-preserving external edits.

To speed up WHERE on an INTEGER column, run `CREATE INDEX idx_age ON students (age)`.
This writes `data/students.age.idx`; queries never create or rebuild index files.
If the CSV changes, queries ignore the out-of-date index until you run CREATE INDEX
again. See [B+ tree indexes](docs/indexing.md).

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
| Optimizer | Fold constants, simplify predicates, merge consecutive projections, choose index scans |
| Executor | Convert plans into fresh operator trees without reading rows |
| Operators | Scan, filtering, stable in-memory sorting, streaming limit, projection, cleanup |
| Storage | Schema/catalog lookup, CSV cursors, B+ tree index scans, new-table writes via Java API |
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
- Output includes column headers. No cost-based optimizer or transactions; indexes
  cover INTEGER columns of single-table WHERE clauses only.

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
  index/                 B+ tree index files
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
- [B+ tree indexes](docs/indexing.md)

## Next work

Consider temporary row storage for external sorting and streaming result output
for large queries. Cached statistics are available for future planning tools;
the current optimizer does not use them. Design aggregation according to course
requirements before extending the current subset.

## Group

- [Group notes](https://docs.google.com/document/d/110spyKtpgsiMZG_rsPnJlrbhwEerZRns3I_K3ArTrzk/edit?usp=sharing)
- [Slack channel](https://app.slack.com/client/T0C1A7U2VJM/C0C1A7UQFFB)
