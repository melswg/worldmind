package io.github.melswg.worldmind.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.github.melswg.worldmind.core.AuthoritativeInitializationPath;
import io.github.melswg.worldmind.core.AuthoritativeWorldmindInitializer;
import io.github.melswg.worldmind.core.WorldmindAuthoritativeRuntime;
import io.github.melswg.worldmind.core.configuration.DisabledWorldmindIntegration;
import io.github.melswg.worldmind.core.configuration.IntegrationDisableReason;
import io.github.melswg.worldmind.core.configuration.SecretAvailability;
import io.github.melswg.worldmind.fabric.configuration.WorldmindStartupConfigurationLoader;
import io.github.melswg.worldmind.testkit.FakeSecretResolver;
import io.github.melswg.worldmind.testkit.WorldmindTestkit;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldmindFabricServerLifecycleTest {
    @TempDir
    Path configurationDirectory;

    @Test
    void dedicatedAndIntegratedServersUseTheSameAuthoritativeInitializationPath() {
        WorldmindAuthoritativeRuntime dedicatedRuntime = startLogicalServer();
        WorldmindAuthoritativeRuntime integratedRuntime = startLogicalServer();

        assertEquals(AuthoritativeInitializationPath.LOGICAL_SERVER, dedicatedRuntime.initializationPath());
        assertEquals(dedicatedRuntime.initializationPath(), integratedRuntime.initializationPath());
    }

    private WorldmindAuthoritativeRuntime startLogicalServer() {
        WorldmindFabricServerLifecycle lifecycle = new WorldmindFabricServerLifecycle(
            new AuthoritativeWorldmindInitializer(),
            new WorldmindStartupConfigurationLoader(
                configurationDirectory,
                ignored -> SecretAvailability.UNREADABLE
            )
        );
        lifecycle.onServerStarted(null);
        return lifecycle.runtime();
    }

    @Test
    void startsTheLogicalServerWhenAnExternalSecretIsUnavailable() throws IOException {
        writeValidConfiguration();
        FakeSecretResolver secrets = WorldmindTestkit.secretResolver().willResolveAs(SecretAvailability.MISSING);
        WorldmindFabricServerLifecycle lifecycle = new WorldmindFabricServerLifecycle(
            new AuthoritativeWorldmindInitializer(),
            new WorldmindStartupConfigurationLoader(configurationDirectory, secrets)
        );

        lifecycle.onServerStarted(null);

        WorldmindAuthoritativeRuntime runtime = lifecycle.runtime();
        DisabledWorldmindIntegration disabled = assertInstanceOf(
            DisabledWorldmindIntegration.class,
            runtime.integrationState()
        );
        assertEquals(AuthoritativeInitializationPath.LOGICAL_SERVER, runtime.initializationPath());
        assertEquals(IntegrationDisableReason.SECRET_UNAVAILABLE, disabled.reason());
        assertEquals(1, secrets.resolutionCount());
    }

    private void writeValidConfiguration() throws IOException {
        Path profile = configurationDirectory.resolve("profiles/oracle");
        Files.createDirectories(profile.resolve("lore"));
        Files.writeString(configurationDirectory.resolve("worldmind.json"), """
            {
              "schemaVersion": 1,
              "enabled": true,
              "activeProfile": "oracle",
              "chatBatching": {
                "maxMessages": 8,
                "maxWaitMillis": 5000,
                "maxEstimatedInputCharacters": 4000
              },
              "provider": {
                "id": "custom-openai-compatible",
                "endpoint": "https://api.example.invalid/v1/chat/completions",
                "model": "example-model",
                "secretReference": "env:WORLDMIND_TEST_KEY",
                "generation": {}
              }
            }
            """, StandardCharsets.UTF_8);
        Files.writeString(profile.resolve("profile.json"), """
            {
              "schemaVersion": 1,
              "characterName": "Aster",
              "personaFile": "persona.md",
              "administratorRulesFile": "rules.md",
              "loreFiles": ["lore/world.md"],
              "responseStyle": "calm",
              "responseLengthLimit": 280
            }
            """, StandardCharsets.UTF_8);
        Files.writeString(profile.resolve("persona.md"), "A calm guide.", StandardCharsets.UTF_8);
        Files.writeString(profile.resolve("rules.md"), "Never claim server authority.", StandardCharsets.UTF_8);
        Files.writeString(profile.resolve("lore/world.md"), "A valley of old stone.", StandardCharsets.UTF_8);
    }
}
