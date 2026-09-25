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


## Run

```bash
mvn -q test
mvn -q package
java -jar target/minidb-0.1.0.jar data
```

The prompt accepts a line and exits on `quit`. Execution throws until `Parser` is filled in.

## Group

- [Group notes](https://docs.google.com/document/d/110spyKtpgsiMZG_rsPnJlrbhwEerZRns3I_K3ArTrzk/edit?usp=sharing)
- [Slack channel](https://app.slack.com/client/T0C1A7U2VJM/C0C1A7UQFFB)

Starting choices: Java, CSV files, integers and strings, then `SELECT` / `FROM` / `JOIN`. Add `WHERE`, `ORDER BY`, `GROUP BY`, more types, and subqueries only after that path works.