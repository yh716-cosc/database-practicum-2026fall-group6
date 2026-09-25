package minidb;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class EngineTest {
    @Test
    void executeIsUnimplemented() {
        Engine engine = new Engine(Path.of("data"));
        assertThrows(UnsupportedOperationException.class, () -> engine.execute("SELECT 1"));
    }
}
