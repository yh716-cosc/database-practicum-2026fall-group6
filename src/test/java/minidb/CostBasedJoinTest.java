package minidb;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CostBasedJoinTest {
    @Test void rightDrivenJoinPreservesLogicalBindingsAndDuplicateRows(@TempDir Path data) throws Exception {
        Files.writeString(data.resolve("a.schema"), "id INTEGER\nname STRING\n");
        Files.writeString(data.resolve("b.schema"), "id INTEGER\nlabel STRING\n");
        Files.writeString(data.resolve("a.csv"), "id,name\n1,A\n2,B\n1,C\n2,D\n1,A\n");
        Files.writeString(data.resolve("b.csv"), "id,label\n2,Y\n1,X\n");
        Engine engine = new Engine(data);
        String sql = "SELECT x.name, y.label FROM a x JOIN b y ON y.id = x.id WHERE x.id >= 1";

        var result = engine.execute(sql);
        assertEquals(List.of("name", "label"), result.columnNames());
        assertEquals(List.of(List.of("B", "Y"), List.of("D", "Y"), List.of("A", "X"),
                List.of("C", "X"), List.of("A", "X")),
                result.rows().stream().map(r -> r.values().stream().map(v -> v.raw()).toList()).toList());
        assertEquals("A", engine.execute(sql + " LIMIT 1").rows().get(0).values().get(0).raw());
    }
}

