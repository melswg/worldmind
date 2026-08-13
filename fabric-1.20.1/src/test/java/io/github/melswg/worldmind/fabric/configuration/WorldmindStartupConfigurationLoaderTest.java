package io.github.melswg.worldmind.fabric.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melswg.worldmind.core.configuration.ConfigurationDiagnostic;
import io.github.melswg.worldmind.core.configuration.ChatNameColor;
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

        writeGlobal(true, "oracle", "{\"temperature\": 0.4, \"maxOutputTokens\": 120}", "env:WORLDMIND_TEST_KEY");
        EnabledWorldmindIntegration oracle = assertInstanceOf(EnabledWorldmindIntegration.class, loader.load());

        writeGlobal(true, "ranger", "{\"topP\": 0.8, \"maxOutputTokens\": 80}", "env:WORLDMIND_TEST_KEY");
        EnabledWorldmindIntegration ranger = assertInstanceOf(EnabledWorldmindIntegration.class, loader.load());

        assertEquals("Aster", oracle.configuration().profile().characterName());
        assertEquals("Measured and curious.", oracle.configuration().profile().persona());
        assertEquals("Never claim server authority.", oracle.configuration().profile().administratorRules());
        assertEquals("World details for Aster.", oracle.configuration().profile().loreMaterials().get(0).content());
        assertEquals("Speak calmly.", oracle.configuration().profile().responseStyle());
        assertEquals(280, oracle.configuration().profile().responseLengthLimit().maxCharacters());
        assertEquals(ChatNameColor.LIGHT_PURPLE, oracle.configuration().profile().chatNameColor());
        assertEquals("Bramble", ranger.configuration().profile().characterName());
        assertEquals("Use short field notes.", ranger.configuration().profile().responseStyle());
        assertEquals(160, ranger.configuration().profile().responseLengthLimit().maxCharacters());
        assertEquals("env:WORLDMIND_TEST_KEY", ranger.configuration().globalConfiguration().provider().secretReference().reference());
        assertEquals(8, ranger.configuration().globalConfiguration().chatBatching().maxMessages());
        assertEquals(5_000, ranger.configuration().globalConfiguration().chatBatching().maxWaitMillis());
        assertEquals(4_000, ranger.configuration().globalConfiguration().chatBatching().maxEstimatedInputCharacters());
        assertEquals(16, ranger.configuration().globalConfiguration().requestQueue().capacity());
        assertEquals(2, ranger.configuration().globalConfiguration().requestQueue().maxConcurrency());
        assertEquals(
            "https://api.example.invalid/v1/chat/completions",
            ranger.configuration().globalConfiguration().provider().endpoint().uri().toString()
        );
        assertEquals(2, secretResolver.resolutionCount());

        String profileJson = Files.readString(profileDirectory("oracle").resolve("profile.json"), StandardCharsets.UTF_8);
        assertFalse(profileJson.contains("secretReference"));
    }

    @Test
    void acceptsEveryExactVanillaChatNameColorAndDefaultsWhenTheOptionalFieldIsAbsent() throws IOException {
        writeGlobal(true, "oracle", "{}", "env:WORLDMIND_TEST_KEY");
        WorldmindStartupConfigurationLoader loader = new WorldmindStartupConfigurationLoader(
            configurationDirectory,
            WorldmindTestkit.secretResolver()
        );

        writeProfile("oracle", "Aster", "Measured and curious.", "Never claim server authority.", "Speak calmly.", 280);
        EnabledWorldmindIntegration defaulted = assertInstanceOf(EnabledWorldmindIntegration.class, loader.load());
        assertEquals(ChatNameColor.LIGHT_PURPLE, defaulted.configuration().profile().chatNameColor());

        for (ChatNameColor color : ChatNameColor.values()) {
            writeProfile(
                "oracle",
                "Aster",
                "Measured and curious.",
                "Never claim server authority.",
                "Speak calmly.",
                280,
                color.profileValue()
            );
            EnabledWorldmindIntegration configured = assertInstanceOf(EnabledWorldmindIntegration.class, loader.load());
            assertEquals(color, configured.configuration().profile().chatNameColor());
        }
    }

    @Test
    void rejectsUnknownOrNonStringChatNameColorAtTheProfileField() throws IOException {
        writeGlobal(true, "oracle", "{}", "env:WORLDMIND_TEST_KEY");
        writeProfile("oracle", "Aster", "Measured and curious.", "Never claim server authority.", "Speak calmly.", 280, "LIGHT_PURPLE");

        DisabledWorldmindIntegration unknown = assertInstanceOf(
            DisabledWorldmindIntegration.class,
            new WorldmindStartupConfigurationLoader(configurationDirectory, WorldmindTestkit.secretResolver()).load()
        );
        assertDiagnostic(unknown.diagnostics(), "profile.chatNameColor", "case-sensitive vanilla color name");

        Path profileFile = profileDirectory("oracle").resolve("profile.json");
        String nonString = Files.readString(profileFile, StandardCharsets.UTF_8)
            .replace("\"chatNameColor\": \"LIGHT_PURPLE\"", "\"chatNameColor\": 3");
        Files.writeString(profileFile, nonString, StandardCharsets.UTF_8);
        DisabledWorldmindIntegration wrongType = assertInstanceOf(
            DisabledWorldmindIntegration.class,
            new WorldmindStartupConfigurationLoader(configurationDirectory, WorldmindTestkit.secretResolver()).load()
        );
        assertDiagnostic(wrongType.diagnostics(), "profile.chatNameColor", "case-sensitive vanilla color name");
    }

    @Test
    void loadsStrictV2DialogueRetentionAndMapsV1ToTheDocumentedDefaults() throws IOException {
        writeProfile("oracle", "Aster", "Measured and curious.", "Never claim server authority.", "Speak calmly.", 280);
        writeGlobal(true, "oracle", "{}", "env:WORLDMIND_TEST_KEY");
        EnabledWorldmindIntegration legacy = assertInstanceOf(EnabledWorldmindIntegration.class,
            new WorldmindStartupConfigurationLoader(configurationDirectory, WorldmindTestkit.secretResolver()).load());
        assertEquals(0, legacy.configuration().globalConfiguration().dialogueRetention().maximumRawAgeDays());
        assertTrue(legacy.configuration().globalConfiguration().dialogueRetention().persistRawObservations());

        Path global = configurationDirectory.resolve("worldmind.json");
        String v2 = Files.readString(global, StandardCharsets.UTF_8)
            .replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")
            .replace("\"provider\": {", "\"dialogueRetention\": {\"persistRawObservations\": false, \"maximumRawAgeDays\": 7, \"useInRecentContext\": false, \"useInCompaction\": true, \"useInRetrieval\": false},\n              \"provider\": {");
        Files.writeString(global, v2, StandardCharsets.UTF_8);
        EnabledWorldmindIntegration current = assertInstanceOf(EnabledWorldmindIntegration.class,
            new WorldmindStartupConfigurationLoader(configurationDirectory, WorldmindTestkit.secretResolver()).load());
        var retention = current.configuration().globalConfiguration().dialogueRetention();
        assertFalse(retention.persistRawObservations());
        assertEquals(7, retention.maximumRawAgeDays());
        assertFalse(retention.useInRecentContext());
        assertTrue(retention.useInCompaction());
        assertFalse(retention.useInRetrieval());
    }

    @Test
    void rejectsUnknownFieldsAndNeverRewritesANewerSchema() throws IOException {
        writeProfile("oracle", "Aster", "Measured and curious.", "Never claim server authority.", "Speak calmly.", 280);
        String futureGlobal = """
            {
              "schemaVersion": 3,
              "enabled": true,
              "activeProfile": "oracle",
              "chatBatching": {"maxMessages": 8, "maxWaitMillis": 5000, "maxEstimatedInputCharacters": 4000},
              "requestQueue": {"capacity": 16, "maxConcurrency": 2},
              "unsupportedFutureField": true,
              "provider": {
                "id": "custom-openai-compatible",
                "endpoint": "https://api.example.invalid/v1/chat/completions",
                "model": "example-model",
                "secretReference": "env:WORLDMIND_TEST_KEY",
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
    void requiresStrictPositiveAndSafelyRepresentableChatBatchingFields() throws IOException {
        writeProfile("oracle", "Aster", "Measured and curious.", "Never claim server authority.", "Speak calmly.", 280);
        writeGlobal(true, "oracle", "{}", "env:WORLDMIND_TEST_KEY");
        Path globalFile = configurationDirectory.resolve("worldmind.json");
        String invalid = Files.readString(globalFile, StandardCharsets.UTF_8)
            .replace("\"maxMessages\": 8", "\"maxMessages\": 0")
            .replace("\"maxWaitMillis\": 5000", "\"maxWaitMillis\": 2147483648")
            .replace("\"maxEstimatedInputCharacters\": 4000", "\"maxEstimatedInputCharacters\": \"many\", \"unknown\": true");
        Files.writeString(globalFile, invalid, StandardCharsets.UTF_8);

        DisabledWorldmindIntegration disabled = assertInstanceOf(
            DisabledWorldmindIntegration.class,
            new WorldmindStartupConfigurationLoader(configurationDirectory, WorldmindTestkit.secretResolver()).load()
        );

        assertDiagnostic(disabled.diagnostics(), "global.chatBatching.maxMessages", "positive");
        assertDiagnostic(disabled.diagnostics(), "global.chatBatching.maxWaitMillis", "integer");
        assertDiagnostic(disabled.diagnostics(), "global.chatBatching.maxEstimatedInputCharacters", "integer");
        assertDiagnostic(disabled.diagnostics(), "global.chatBatching.unknown", "not supported by the strict v1 schema");
        assertEquals(invalid, Files.readString(globalFile, StandardCharsets.UTF_8));
    }

    @Test
    void reportsMissingChatBatchingAsAFieldLevelStartupDiagnostic() throws IOException {
        writeProfile("oracle", "Aster", "Measured and curious.", "Never claim server authority.", "Speak calmly.", 280);
        writeGlobal(true, "oracle", "{}", "env:WORLDMIND_TEST_KEY");
        Path globalFile = configurationDirectory.resolve("worldmind.json");
        String missing = Files.readString(globalFile, StandardCharsets.UTF_8).replaceAll(
            "(?s)\\s*\\\"chatBatching\\\": \\{.*?\\},(?=\\s*\\\"provider\\\")",
            ""
        );
        Files.writeString(globalFile, missing, StandardCharsets.UTF_8);

        DisabledWorldmindIntegration disabled = assertInstanceOf(
            DisabledWorldmindIntegration.class,
            new WorldmindStartupConfigurationLoader(configurationDirectory, WorldmindTestkit.secretResolver()).load()
        );

        assertDiagnostic(disabled.diagnostics(), "global.chatBatching", "is required");
    }

    @Test
    void requiresStrictPositiveRequestQueueFieldsBeforeStartingTheIntegration() throws IOException {
        writeProfile("oracle", "Aster", "Measured and curious.", "Never claim server authority.", "Speak calmly.", 280);
        writeGlobal(true, "oracle", "{}", "env:WORLDMIND_TEST_KEY");
        Path globalFile = configurationDirectory.resolve("worldmind.json");
        String invalid = Files.readString(globalFile, StandardCharsets.UTF_8)
            .replace("\"capacity\": 16", "\"capacity\": 0")
            .replace("\"maxConcurrency\": 2", "\"maxConcurrency\": \"many\", \"unknown\": true");
        Files.writeString(globalFile, invalid, StandardCharsets.UTF_8);

        DisabledWorldmindIntegration disabled = assertInstanceOf(
            DisabledWorldmindIntegration.class,
            new WorldmindStartupConfigurationLoader(configurationDirectory, WorldmindTestkit.secretResolver()).load()
        );

        assertDiagnostic(disabled.diagnostics(), "global.requestQueue.capacity", "positive");
        assertDiagnostic(disabled.diagnostics(), "global.requestQueue.maxConcurrency", "integer");
        assertDiagnostic(disabled.diagnostics(), "global.requestQueue.unknown", "not supported by the strict v1 schema");
        assertEquals(invalid, Files.readString(globalFile, StandardCharsets.UTF_8));
    }

    @Test
    void reportsMissingRequestQueueAsAFieldLevelStartupDiagnostic() throws IOException {
        writeProfile("oracle", "Aster", "Measured and curious.", "Never claim server authority.", "Speak calmly.", 280);
        writeGlobal(true, "oracle", "{}", "env:WORLDMIND_TEST_KEY");
        Path globalFile = configurationDirectory.resolve("worldmind.json");
        String missing = Files.readString(globalFile, StandardCharsets.UTF_8).replaceAll(
            "(?s)\\s*\\\"requestQueue\\\": \\{.*?\\},(?=\\s*\\\"provider\\\")",
            ""
        );
        Files.writeString(globalFile, missing, StandardCharsets.UTF_8);

        DisabledWorldmindIntegration disabled = assertInstanceOf(
            DisabledWorldmindIntegration.class,
            new WorldmindStartupConfigurationLoader(configurationDirectory, WorldmindTestkit.secretResolver()).load()
        );

        assertDiagnostic(disabled.diagnostics(), "global.requestQueue", "is required");
    }

    @Test
    void rejectsZeroAndNegativeChatBatchingValuesWithoutStartingTheIntegration() throws IOException {
        writeProfile("oracle", "Aster", "Measured and curious.", "Never claim server authority.", "Speak calmly.", 280);
        writeGlobal(true, "oracle", "{}", "env:WORLDMIND_TEST_KEY");
        Path globalFile = configurationDirectory.resolve("worldmind.json");
        String nonPositive = Files.readString(globalFile, StandardCharsets.UTF_8)
            .replace("\"maxMessages\": 8", "\"maxMessages\": -1")
            .replace("\"maxWaitMillis\": 5000", "\"maxWaitMillis\": 0")
            .replace("\"maxEstimatedInputCharacters\": 4000", "\"maxEstimatedInputCharacters\": -1");
        Files.writeString(globalFile, nonPositive, StandardCharsets.UTF_8);

        DisabledWorldmindIntegration disabled = assertInstanceOf(
            DisabledWorldmindIntegration.class,
            new WorldmindStartupConfigurationLoader(configurationDirectory, WorldmindTestkit.secretResolver()).load()
        );

        assertDiagnostic(disabled.diagnostics(), "global.chatBatching.maxMessages", "positive");
        assertDiagnostic(disabled.diagnostics(), "global.chatBatching.maxWaitMillis", "positive");
        assertDiagnostic(disabled.diagnostics(), "global.chatBatching.maxEstimatedInputCharacters", "positive");
    }

    @Test
    void refusesAnUnrecognizedSchemaVersionWithoutGuessingAMigration() throws IOException {
        writeProfile("oracle", "Aster", "Measured and curious.", "Never claim server authority.", "Speak calmly.", 280);
        writeGlobal(true, "oracle", "{}", "env:WORLDMIND_TEST_KEY");
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
        writeGlobal(true, "oracle", "{}", "env:WORLDMIND_TEST_KEY");
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
        writeGlobal(true, "oracle", "{}", "env:WORLDMIND_TEST_KEY");
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
        writeGlobal(false, "oracle", "{}", "env:WORLDMIND_TEST_KEY");
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

    @Test
    void validatesCustomEndpointProviderAndEnvironmentReferenceBeforeSecretResolution() throws IOException {
        writeProfile("oracle", "Aster", "Measured and curious.", "Never claim server authority.", "Speak calmly.", 280);
        FakeSecretResolver secrets = WorldmindTestkit.secretResolver();
        WorldmindStartupConfigurationLoader loader = new WorldmindStartupConfigurationLoader(configurationDirectory, secrets);

        writeGlobal(
            true,
            "oracle",
            "{}",
            "custom-openai-compatible",
            "http://127.0.0.1:8090/v1/chat/completions?local=true",
            "env:WORLDMIND_TEST_KEY"
        );
        assertInstanceOf(EnabledWorldmindIntegration.class, loader.load());

        writeGlobal(
            true,
            "oracle",
            "{}",
            "custom-openai-compatible",
            "http://[::1]:8090/v1/chat/completions",
            "env:WORLDMIND_TEST_KEY"
        );
        assertInstanceOf(EnabledWorldmindIntegration.class, loader.load());

        writeGlobal(
            true,
            "oracle",
            "{}",
            "custom-openai-compatible",
            "http://provider.example/v1/chat/completions",
            "env:WORLDMIND_TEST_KEY"
        );
        DisabledWorldmindIntegration remoteHttp = assertInstanceOf(DisabledWorldmindIntegration.class, loader.load());
        assertDiagnostic(remoteHttp.diagnostics(), "global.provider.endpoint", "must use HTTPS");

        writeGlobal(
            true,
            "oracle",
            "{}",
            "custom-openai-compatible",
            "https://operator@provider.example/v1/chat/completions#fragment",
            "env:WORLDMIND_TEST_KEY"
        );
        DisabledWorldmindIntegration credentialBearingEndpoint = assertInstanceOf(
            DisabledWorldmindIntegration.class,
            loader.load()
        );
        assertDiagnostic(credentialBearingEndpoint.diagnostics(), "global.provider.endpoint", "must not contain user-info");

        writeGlobal(
            true,
            "oracle",
            "{}",
            "another-provider",
            "https://provider.example/v1/chat/completions",
            "plain-text-reference"
        );
        DisabledWorldmindIntegration unsupported = assertInstanceOf(DisabledWorldmindIntegration.class, loader.load());
        assertDiagnostic(unsupported.diagnostics(), "global.provider.id", "custom-openai-compatible");
        assertDiagnostic(unsupported.diagnostics(), "global.provider.secretReference", "provider-scheme:opaque-reference");
        assertEquals(2, secrets.resolutionCount());

        String missingEndpoint = Files.readString(configurationDirectory.resolve("worldmind.json"), StandardCharsets.UTF_8)
            .replace("\"endpoint\": \"https://provider.example/v1/chat/completions\",\n", "");
        Files.writeString(configurationDirectory.resolve("worldmind.json"), missingEndpoint, StandardCharsets.UTF_8);
        DisabledWorldmindIntegration missing = assertInstanceOf(DisabledWorldmindIntegration.class, loader.load());
        assertDiagnostic(missing.diagnostics(), "global.provider.endpoint", "is required");
        assertEquals(missingEndpoint, Files.readString(configurationDirectory.resolve("worldmind.json"), StandardCharsets.UTF_8));
    }

    private void writeGlobal(
        boolean enabled,
        String activeProfile,
        String generation,
        String secretReference
    ) throws IOException {
        writeGlobal(
            enabled,
            activeProfile,
            generation,
            "custom-openai-compatible",
            "https://api.example.invalid/v1/chat/completions",
            secretReference
        );
    }

    private void writeGlobal(
        boolean enabled,
        String activeProfile,
        String generation,
        String providerId,
        String endpoint,
        String secretReference
    ) throws IOException {
        Files.createDirectories(configurationDirectory);
        Files.writeString(configurationDirectory.resolve("worldmind.json"), """
            {
              "schemaVersion": 1,
              "enabled": %s,
              "activeProfile": "%s",
              "chatBatching": {
                "maxMessages": 8,
                "maxWaitMillis": 5000,
                "maxEstimatedInputCharacters": 4000
              },
              "requestQueue": {"capacity": 16, "maxConcurrency": 2},
              "provider": {
                "id": "%s",
                "endpoint": "%s",
                "model": "example-model",
                "secretReference": "%s",
                "timeouts": {"connectMillis": 5000, "responseCompletionMillis": 30000},
                "retry": {"maximumAttempts": 3, "initialBackoffMillis": 250, "maximumBackoffMillis": 4000, "jitterRatio": 0.2},
                "circuitBreaker": {"failureThreshold": 5, "cooldownMillis": 30000},
                "generation": %s
              }
            }
            """.formatted(enabled, activeProfile, providerId, endpoint, secretReference, generation), StandardCharsets.UTF_8);
    }

    private void writeProfile(
        String profileId,
        String characterName,
        String persona,
        String administratorRules,
        String responseStyle,
        int responseLengthLimit
    ) throws IOException {
        writeProfile(
            profileId,
            characterName,
            persona,
            administratorRules,
            responseStyle,
            responseLengthLimit,
            null
        );
    }

    private void writeProfile(
        String profileId,
        String characterName,
        String persona,
        String administratorRules,
        String responseStyle,
        int responseLengthLimit,
        String chatNameColor
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
              "responseLengthLimit": %d%s
            }
            """.formatted(
                characterName,
                responseStyle,
                responseLengthLimit,
                chatNameColor == null ? "" : ",\n  \"chatNameColor\": \"" + chatNameColor + "\""
            ), StandardCharsets.UTF_8);
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
