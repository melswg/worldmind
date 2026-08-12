package io.github.melswg.worldmind.fabric;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class WorldmindFabricMetadataTest {
    @Test
    void exposesOnlyTheCommonEntrypointWithoutAClientEntrypoint() throws IOException {
        String metadata = readMetadata();

        assertTrue(metadata.contains("\"id\": \"worldmind\""));
        assertTrue(metadata.contains("\"minecraft\": \"~1.20.1\""));
        assertTrue(metadata.contains("\"environment\": \"*\""));
        assertTrue(metadata.contains("\"main\""));
        assertFalse(metadata.contains("\"client\""));
    }

    private String readMetadata() throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/fabric.mod.json")) {
            if (input == null) {
                throw new IOException("fabric.mod.json is missing from the Fabric module.");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
