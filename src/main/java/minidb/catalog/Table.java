package minidb.catalog;

import java.util.List;

public record Table(String name, List<Column> columns) {
}
