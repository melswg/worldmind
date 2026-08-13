package io.github.melswg.worldmind.example.context;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ExampleFabricMetadataTest {
    @Test
    void exposesOnlyTheVersionedWorldmindEntrypointWithoutAClientEntrypoint() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/fabric.mod.json")) {
            String metadata = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(metadata.contains("\"worldmind-game-context-v1\""));
            assertTrue(metadata.contains("\">=0.1.0 <0.2.0\""));
            assertFalse(metadata.contains("\"client\""));
        }
    }
}
