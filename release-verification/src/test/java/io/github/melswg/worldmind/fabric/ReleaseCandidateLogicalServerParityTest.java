package io.github.melswg.worldmind.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melswg.worldmind.core.AuthoritativeInitializationPath;
import io.github.melswg.worldmind.core.AuthoritativeWorldmindInitializer;
import io.github.melswg.worldmind.core.WorldmindAuthoritativeRuntime;
import io.github.melswg.worldmind.core.configuration.SecretAvailability;
import io.github.melswg.worldmind.fabric.configuration.WorldmindStartupConfigurationLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** CI parity evidence for the logical-server path; graphical client smoke remains a local operator action. */
class ReleaseCandidateLogicalServerParityTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void stagedCandidateHasOnlyCommonEntrypointAndBothLogicalServerFormsShareIt() throws Exception {
        Path candidate = Path.of(System.getProperty("worldmind.release.candidate"));
        assertTrue(Files.isRegularFile(candidate), "The parity test must consume the staged remapped JAR.");
        try (ZipFile artifact = new ZipFile(candidate.toFile())) {
            String metadata = new String(artifact.getInputStream(artifact.getEntry("fabric.mod.json")).readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(metadata.contains("\"main\""));
            assertFalse(metadata.contains("\"client\""));
            assertNotNull(artifact.getEntry("io/github/melswg/worldmind/fabric/WorldmindFabricMod.class"));
        }
        assertFalse(Arrays.stream(WorldmindFabricMod.class.getInterfaces())
            .anyMatch(type -> type.getName().contains("client")), "The common entrypoint must not be a client initializer.");

        WorldmindAuthoritativeRuntime dedicated = start(temporaryDirectory.resolve("dedicated"));
        WorldmindAuthoritativeRuntime integrated = start(temporaryDirectory.resolve("integrated"));
        assertEquals(AuthoritativeInitializationPath.LOGICAL_SERVER, dedicated.initializationPath());
        assertEquals(dedicated.initializationPath(), integrated.initializationPath());
        assertTrue(Files.readString(Path.of(System.getProperty("worldmind.release.manifest"))).contains("\"logical-server\""));
    }

    private static WorldmindAuthoritativeRuntime start(Path configurationDirectory) {
        WorldmindFabricServerLifecycle lifecycle = new WorldmindFabricServerLifecycle(
            new AuthoritativeWorldmindInitializer(),
            new WorldmindStartupConfigurationLoader(configurationDirectory, ignored -> SecretAvailability.UNREADABLE)
        );
        lifecycle.onServerStarted(null);
        return lifecycle.runtime();
    }
}
