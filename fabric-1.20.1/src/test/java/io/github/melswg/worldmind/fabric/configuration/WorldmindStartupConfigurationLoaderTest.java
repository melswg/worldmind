package io.github.melswg.worldmind.fabric.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melswg.worldmind.core.configuration.ConfigurationDiagnostic;
import io.github.melswg.worldmind.core.configuration.DisabledWorldmindIntegration;
import io.github.melswg.worldmind.core.configuration.EnabledWorldmindIntegration;
import io.github.melswg.worldmind.core.configuration.IntegrationDisableReason;
import io.github.melswg.worldmind.core.configuration.SecretAvailability;
import io.github.melswg.worldmind.core.configuration.WorldmindIntegrationState;
import io.github.melswg.worldmind.testkit.FakeSecretResolver;
import io.github.melswg.worldmind.testkit.WorldmindTestkit;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldmindStartupConfigurationLoaderTest {
    @TempDir
    Path configurationDirectory;

    @Test
    void loadsDistinctPortableProfilesFromTheSameBinaryWithoutProfileSecrets() throws IOException {
        writeProfile("oracle", "Aster", "Measured and curious.", "Never claim server authority.", "Speak calmly.", 280);
        writeProfile("ranger", "Bramble", "Watchful and practical.", "Never disclose private player data.", "Use short field notes.", 160);
        FakeSecretResolver secretResolver = WorldmindTestkit.secretResolver();
        WorldmindStartupConfigurationLoader loader = new WorldmindStartupConfigurationLoader(
            configurationDirectory,
            secretResolver
        );

        writeGlobal(true, "oracle", "{\"temperature\": 0.4, \"maxOutputTokens\": 120}", "server-managed-reference");
        EnabledWorldmindIntegration oracle = assertInstanceOf(EnabledWorldmindIntegration.class, loader.load());

        writeGlobal(true, "ranger", "{\"topP\": 0.8, \"maxOutputTokens\": 80}", "server-managed-reference");
        EnabledWorldmindIntegration ranger = assertInstanceOf(EnabledWorldmindIntegration.class, loader.load());

        assertEquals("Aster", oracle.configuration().profile().characterName());
        assertEquals("Measured and curious.", oracle.configuration().profile().persona());
        assertEquals("Never claim server authority.", oracle.configuration().profile().administratorRules());
        assertEquals("World details for Aster.", oracle.configuration().profile().loreMaterials().get(0).content());
        assertEquals("Speak calmly.", oracle.configuration().profile().responseStyle());
        assertEquals(280, oracle.configuration().profile().responseLengthLimit().maxCharacters());
        assertEquals("Bramble", ranger.configuration().profile().characterName());
        assertEquals("Use short field notes.", ranger.configuration().profile().responseStyle());
        assertEquals(160, ranger.configuration().profile().responseLengthLimit().maxCharacters());
        assertEquals("server-managed-reference", ranger.configuration().globalConfiguration().provider().secretReference().reference());
        assertEquals(2, secretResolver.resolutionCount());

        String profileJson = Files.readString(profileDirectory("oracle").resolve("profile.json"), StandardCharsets.UTF_8);
        assertFalse(profileJson.contains("secretReference"));
    }

    @Test
    void rejectsUnknownFieldsAndNeverRewritesANewerSchema() throws IOException {
        writeProfile("oracle", "Aster", "Measured and curious.", "Never claim server authority.", "Speak calmly.", 280);
        String futureGlobal = """
            {
              "schemaVersion": 2,
              "enabled": true,
              "activeProfile": "oracle",
              "unsupportedFutureField": true,
              "provider": {
                "id": "custom-openai-compatible",
                "model": "example-model",
                "secretReference": "server-managed-reference",
                "unsupportedProviderField": true,
                "generation": {"temperature": 0.4, "topP": 0.8}
              }
            }
            """;
        Path globalFile = configurationDirectory.resolve("worldmind.json");
        Files.writeString(globalFile, futureGlobal, StandardCharsets.UTF_8);

        DisabledWorldmindIntegration disabled = assertInstanceOf(
            DisabledWorldmindIntegration.class,
            new WorldmindStartupConfigurationLoader(configurationDirectory, WorldmindTestkit.secretResolver()).load()
        );

        assertEquals(IntegrationDisableReason.INVALID_CONFIGURATION, disabled.reason());
        assertDiagnostic(disabled.diagnostics(), "global.schemaVersion", "exactly supported schema version 1");
        assertDiagnostic(disabled.diagnostics(), "global.unsupportedFutureField", "not supported by the strict v1 schema");
        assertDiagnostic(
            disabled.diagnostics(),
            "global.provider.unsupportedProviderField",
            "not supported by the strict v1 schema"
        );
        assertDiagnostic(disabled.diagnostics(), "global.provider.generation", "temperature and topP cannot both be configured");
        assertEquals(futureGlobal, Files.readString(globalFile, StandardCharsets.UTF_8));
    }

    @Test
    void refusesAnUnrecognizedSchemaVersionWithoutGuessingAMigration() throws IOException {
        writeProfile("oracle", "Aster", "Measured and curious.", "Never claim server authority.", "Speak calmly.", 280);
        writeGlobal(true, "oracle", "{}", "server-managed-reference");
        Path globalFile = configurationDirectory.resolve("worldmind.json");
        String unsupportedVersion = Files.readString(globalFile, StandardCharsets.UTF_8)
            .replace("\"schemaVersion\": 1", "\"schemaVersion\": 0");
        Files.writeString(globalFile, unsupportedVersion, StandardCharsets.UTF_8);

        DisabledWorldmindIntegration disabled = assertInstanceOf(
            DisabledWorldmindIntegration.class,
            new WorldmindStartupConfigurationLoader(configurationDirectory, WorldmindTestkit.secretResolver()).load()
        );

        assertEquals(IntegrationDisableReason.INVALID_CONFIGURATION, disabled.reason());
        assertDiagnostic(disabled.diagnostics(), "global.schemaVersion", "exactly supported schema version 1");
        assertEquals(unsupportedVersion, Files.readString(globalFile, StandardCharsets.UTF_8));
    }

    @Test
    void reportsEveryInvalidProfileFieldWithAHumanReadableReason() throws IOException {
        writeGlobal(true, "oracle", "{}", "server-managed-reference");
        Path profile = profileDirectory("oracle");
        Files.createDirectories(profile.resolve("lore"));
        Files.writeString(profile.resolve("persona.md"), "A calm guide.", StandardCharsets.UTF_8);
        Files.writeString(profile.resolve("lore/world.md"), "A valley of old stone.", StandardCharsets.UTF_8);
        Files.writeString(profile.resolve("profile.json"), """
            {
              "schemaVersion": 1,
              "characterName": "Aster",
              "personaFile": "persona.md",
              "loreFiles": ["lore/world.md"],
              "responseStyle": "calm",
              "responseLengthLimit": 0,
              "secretReference": "not-allowed-in-a-profile"
            }
            """, StandardCharsets.UTF_8);

        DisabledWorldmindIntegration disabled = assertInstanceOf(
            DisabledWorldmindIntegration.class,
            new WorldmindStartupConfigurationLoader(configurationDirectory, WorldmindTestkit.secretResolver()).load()
        );

        assertEquals(IntegrationDisableReason.INVALID_CONFIGURATION, disabled.reason());
        assertDiagnostic(disabled.diagnostics(), "profile.administratorRulesFile", "is required");
        assertDiagnostic(disabled.diagnostics(), "profile.responseLengthLimit", "positive number of characters");
        assertDiagnostic(disabled.diagnostics(), "profile.secretReference", "not supported by the strict v1 schema");
        assertTrue(disabled.diagnostics().stream().allMatch(diagnostic -> diagnostic.message().contains(":")));
    }

    @Test
    void keepsMinecraftRunningInADiagnosableDisabledStateWhenSecretMaterialIsMissingOrUnreadable() throws IOException {
        writeProfile("oracle", "Aster", "Measured and curious.", "Never claim server authority.", "Speak calmly.", 280);
        writeGlobal(true, "oracle", "{}", "server-managed-reference");
        FakeSecretResolver secretResolver = WorldmindTestkit.secretResolver();
        WorldmindStartupConfigurationLoader loader = new WorldmindStartupConfigurationLoader(
            configurationDirectory,
            secretResolver
        );

        secretResolver.willResolveAs(SecretAvailability.MISSING);
        DisabledWorldmindIntegration missing = assertInstanceOf(DisabledWorldmindIntegration.class, loader.load());
        secretResolver.willResolveAs(SecretAvailability.UNREADABLE);
        DisabledWorldmindIntegration unreadable = assertInstanceOf(DisabledWorldmindIntegration.class, loader.load());

        assertEquals(IntegrationDisableReason.SECRET_UNAVAILABLE, missing.reason());
        assertDiagnostic(missing.diagnostics(), "global.provider.secretReference", "Secret material is missing");
        assertEquals(IntegrationDisableReason.SECRET_UNAVAILABLE, unreadable.reason());
        assertDiagnostic(unreadable.diagnostics(), "global.provider.secretReference", "unavailable or unreadable");
        assertEquals(2, secretResolver.resolutionCount());
        assertNotNull(secretResolver.lastReference());
    }

    @Test
    void respectsOperatorDisableWithoutResolvingExternalSecretMaterial() throws IOException {
        writeProfile("oracle", "Aster", "Measured and curious.", "Never claim server authority.", "Speak calmly.", 280);
        writeGlobal(false, "oracle", "{}", "server-managed-reference");
        FakeSecretResolver secretResolver = WorldmindTestkit.secretResolver();

        WorldmindIntegrationState state = new WorldmindStartupConfigurationLoader(
            configurationDirectory,
            secretResolver
        ).load();

        DisabledWorldmindIntegration disabled = assertInstanceOf(DisabledWorldmindIntegration.class, state);
        assertEquals(IntegrationDisableReason.DISABLED_BY_OPERATOR, disabled.reason());
        assertDiagnostic(disabled.diagnostics(), "global.enabled", "disabled by configuration");
        assertEquals(0, secretResolver.resolutionCount());
    }

    private void writeGlobal(
        boolean enabled,
        String activeProfile,
        String generation,
        String secretReference
    ) throws IOException {
        Files.createDirectories(configurationDirectory);
        Files.writeString(configurationDirectory.resolve("worldmind.json"), """
            {
              "schemaVersion": 1,
              "enabled": %s,
              "activeProfile": "%s",
              "provider": {
                "id": "custom-openai-compatible",
                "model": "example-model",
                "secretReference": "%s",
                "generation": %s
              }
            }
            """.formatted(enabled, activeProfile, secretReference, generation), StandardCharsets.UTF_8);
    }

    private void writeProfile(
        String profileId,
        String characterName,
        String persona,
        String administratorRules,
        String responseStyle,
        int responseLengthLimit
    ) throws IOException {
        Path profile = profileDirectory(profileId);
        Files.createDirectories(profile.resolve("lore"));
        Files.writeString(profile.resolve("persona.md"), persona, StandardCharsets.UTF_8);
        Files.writeString(profile.resolve("rules.md"), administratorRules, StandardCharsets.UTF_8);
        Files.writeString(profile.resolve("lore/world.md"), "World details for " + characterName + ".", StandardCharsets.UTF_8);
        Files.writeString(profile.resolve("profile.json"), """
            {
              "schemaVersion": 1,
              "characterName": "%s",
              "personaFile": "persona.md",
              "administratorRulesFile": "rules.md",
              "loreFiles": ["lore/world.md"],
              "responseStyle": "%s",
              "responseLengthLimit": %d
            }
            """.formatted(characterName, responseStyle, responseLengthLimit), StandardCharsets.UTF_8);
    }

    private Path profileDirectory(String profileId) {
        return configurationDirectory.resolve("profiles").resolve(profileId);
    }

    private void assertDiagnostic(List<ConfigurationDiagnostic> diagnostics, String field, String reasonPart) {
        assertTrue(
            diagnostics.stream().anyMatch(diagnostic ->
                diagnostic.field().equals(field) && diagnostic.reason().contains(reasonPart)
            ),
            () -> "Expected diagnostic for " + field + " containing '" + reasonPart + "' but got " + diagnostics
        );
    }
}
