# minidb

CS 4321/5321 Group 6 skeleton for a simple analytical database. The pieces compile and the command line starts. Query execution is intentionally unimplemented.

Course constraints this layout follows:

- The database is a directory of files on disk and does not change while the system runs.
- Users type queries at a command line.
- The query language is a subset of SQL over relational data.
- There is no update path and no transaction path.
- Rows are pulled one at a time so a later version can run on data larger than memory.

Starting choices, all replaceable: Java, CSV files, integers and strings, then `SELECT` / `FROM` / `JOIN`. Add `WHERE`, `ORDER BY`, `GROUP BY`, more types, and subqueries only after that path works.

## Layout

```
src/main/java/minidb/
  Main.java              program entry
  Engine.java            parse, then plan, then execute
  cli/Cli.java           command line
  sql/Parser.java        SQL text to a statement
  sql/ast/               statement and expression nodes
  catalog/               table and column metadata
  storage/               file scans that yield one row at a time
  types/                 integer and string values
  plan/Planner.java      statement to a logical plan
  exec/                  operators and result printing
data/                    put table files here; nothing is checked in
```

Replace any class behind its interface. Do not add a library that already parses or runs SQL.

## Run

```bash
mvn -q test
mvn -q package
java -jar target/minidb-0.1.0.jar data
```

The prompt accepts a line and exits on `quit`. Execution throws until `Parser` is filled in.
