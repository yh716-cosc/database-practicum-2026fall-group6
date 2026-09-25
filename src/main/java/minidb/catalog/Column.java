package minidb.catalog;

import minidb.types.DataType;

public record Column(String name, DataType type) {
}
