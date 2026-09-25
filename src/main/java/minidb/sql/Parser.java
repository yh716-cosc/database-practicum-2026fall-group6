package minidb.sql;

import minidb.sql.ast.Statement;

public interface Parser {
    Statement parse(String sql);
}
