# Shared CLI test data

This directory contains synthetic sample data for manual query verification.
Despite its name, it is tracked in Git so all group members can use the same data.
`students.csv` has 11 rows; `students.schema` defines the column types.

From the repository root, with Java 17+ and Maven installed:

```sh
mvn -q package
java -jar target/minidb-0.1.0.jar local-test-data
```

Enter one complete SQL statement per line at `minidb>`. Type `quit` to exit.

| Query | Expected result |
| --- | --- |
| `SELECT * FROM students;` | 11 rows, including the repeated Alice row |
| `SELECT name FROM students WHERE age >= 18 AND score >= 85;` | Alice, Carol, O'Brien, alice, Max, Alice (in this order) |
| `SELECT id FROM students WHERE name = 'O''Brien';` | 4 |
| `SELECT id FROM students WHERE name = 'Smith, Bob';` | 5 |
| `SELECT id FROM students WHERE name = '';` | 6 |
| `SELECT id FROM students WHERE name = 'Alice';` | 1, 1 (case-sensitive matching) |
| `SELECT id FROM students WHERE score < 0;` | 9 |
| `SELECT id FROM students WHERE score = 2147483647;` | 10 |
| `SELECT id FROM students WHERE age > 100;` | 0 rows |
| `SELECT id FROM students WHERE age = '18';` | Type error; the prompt remains usable |

Output displays values separated by ` | `, with strings in single quotes.
The extreme scores intentionally test signed 32-bit integer boundaries, rather
than realistic student scores. Keep the sample unchanged when comparing results.
