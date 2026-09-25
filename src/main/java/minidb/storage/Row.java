package minidb.storage;

import java.util.List;

import minidb.types.Value;

public record Row(List<Value> values) {
}
