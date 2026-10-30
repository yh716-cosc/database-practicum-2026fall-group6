# ORDER BY and LIMIT

```sql
SELECT name FROM students
WHERE age >= 18
ORDER BY age DESC, name ASC
LIMIT 5;
```

The logical plan (parent pulls from child) is:

```text
Project(name)
  Limit(5)
    Sort(age DESC, name ASC)
      Filter(age >= 18)
        Scan(students)
```

ORDER BY accepts named columns, optionally table-qualified, with multiple keys.
ASC is the default. INTEGER keys compare numerically without subtraction overflow;
STRING keys use Java String.compareTo (case-sensitive UTF-16 lexicographic order,
not locale collation). Equal keys preserve input order; duplicates remain.
Sorting can use columns absent from SELECT because projection happens last.
Aliases, ordinal keys such as ORDER BY 1, expressions, OFFSET, and SQL NULL are
unsupported. LIMIT accepts a non-negative signed-32-bit-range integer literal.

SortOperator is blocking: its first next() reads all child rows, sorts them in
memory, and then returns rows one at a time. It uses O(n) row-buffer memory and
O(n log n) sorting time for a fixed number of keys. No disk spill or TopN exists.
LimitOperator is streaming: it requests at most N child rows and returns EOF
without requesting an extra row. EOF or explicit close closes the child once;
open, read, and sort-comparison failures close owned resources and suppress cleanup
errors on the original exception. Both operators are single-use.

LIMIT can leave data unread: bad data values in unread rows are not detected.
ORDER BY normally reads all qualifying input before LIMIT emits a row, so
ORDER BY ... LIMIT 1 still validates all scanned rows. LIMIT 0 skips row reads,
including sorting, but opens the operator tree: metadata, missing-file, and CSV
header errors can still occur. All names/types are validated before execution.

## File responsibilities

| File | Responsibility |
| --- | --- |
| sql/SqlParser.java | Recognize ORDER BY keys/directions and LIMIT after WHERE; reject malformed clause order and invalid counts |
| sql/ast/OrderBy.java | Unbound column and ascending/descending direction |
| sql/ast/Query.java | Store ordering and optional limit; retain the four-argument constructor for existing callers |
| plan/Planner.java | Bind ordering columns to source indexes; build Filter, Sort, Limit, Project in order |
| plan/LogicalOperator.java | SortKey, Sort, and Limit plan records |
| plan/Optimizer.java | Recursively optimize new nodes, merge adjacent limits using min, remove identical adjacent sorts |
| exec/Executor.java | Compile logical Sort/Limit into runtime operators without reading rows |
| exec/UnaryOperator.java | Shared single-use lifecycle and child cleanup for the two new operators |
| exec/SortOperator.java | Buffer rows and perform stable multi-key comparisons |
| exec/LimitOperator.java | Count emitted rows and stop without overreading |
| Engine.java | Preserve column labels through Sort/Limit and execute the optimized plan |

ORDER, BY, ASC, DESC, and LIMIT remain contextual identifier tokens, so existing
column/table names remain valid. No tokenizer change is needed.

Optimizer rules only combine directly adjacent limits or identical sorts; they
never move a sort across a limit. TopN and projection pushdown are future work.

Tests in SqlParserTest, PlannerTest, OptimizerTest, SortLimitOperatorTest, and
EngineTest cover syntax, binding, rewrite boundaries, stable sorting, integer
boundaries, LIMIT 0, early termination, storage errors, and failure cleanup.
