package io.github.melswg.worldmind.fabric.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.melswg.worldmind.core.configuration.ExternalSecretReference;
import io.github.melswg.worldmind.core.configuration.ChatBatchingConfiguration;
import io.github.melswg.worldmind.core.configuration.GenerationParameters;
import io.github.melswg.worldmind.core.configuration.LoreMaterial;
import io.github.melswg.worldmind.core.configuration.ProviderConfiguration;
import io.github.melswg.worldmind.core.configuration.ProviderEndpoint;
import io.github.melswg.worldmind.core.configuration.RequestQueueConfiguration;
import io.github.melswg.worldmind.core.configuration.ResponseLengthLimit;
import io.github.melswg.worldmind.core.configuration.SecretAvailability;
import io.github.melswg.worldmind.core.configuration.ValidatedWorldmindConfiguration;
import io.github.melswg.worldmind.core.configuration.WorldmindGlobalConfiguration;
import io.github.melswg.worldmind.core.configuration.WorldmindProfile;
import io.github.melswg.worldmind.core.conversation.ConversationOutcome;
import io.github.melswg.worldmind.core.conversation.ConversationRefusal;
import io.github.melswg.worldmind.core.conversation.DirectReply;
import io.github.melswg.worldmind.core.conversation.AddressingSignal;
import io.github.melswg.worldmind.core.conversation.ChatBatchSealReason;
import io.github.melswg.worldmind.core.conversation.ObservedPublicChatMessage;
import io.github.melswg.worldmind.core.conversation.ProviderCapabilities;
import io.github.melswg.worldmind.core.conversation.RefusalCode;
import io.github.melswg.worldmind.core.conversation.SealedChatBatch;
import io.github.melswg.worldmind.core.conversation.ServerRequester;
import io.github.melswg.worldmind.core.conversation.UntrustedContext;
import io.github.melswg.worldmind.core.conversation.WorldIdentity;
import io.github.melswg.worldmind.testkit.FakeOpenAiCompatibleHttpServer;
import io.github.melswg.worldmind.testkit.WorldmindAcceptanceScenario;
import io.github.melswg.worldmind.testkit.WorldmindTestkit;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CustomOpenAiCompatibleLanguageModelTest {
    private static final Duration ASYNC_TIMEOUT = Duration.ofSeconds(5);
    private static final UUID PLAYER_ID = UUID.fromString("e61d68f6-c42b-4e36-8a33-dab0d4c51c19");
    private static final WorldIdentity WORLD_ID = new WorldIdentity("world-save-opaque-id");

    @Test
    void mapsTheValidatedConversationToAsyncChatCompletionsWithoutExposingOrchestrationMetadata() throws Exception {
        try (FakeOpenAiCompatibleHttpServer server = new FakeOpenAiCompatibleHttpServer()) {
            SyntheticCredentialResolver credentials = SyntheticCredentialResolver.available();
            server.expectBearerCredential(credentials.materialForFakeServer());
            server.respondWith(200, successfulResponse("DIRECT_REPLY\nThe observatory is quiet."));
            server.holdResponses();
            ProviderConfiguration provider = provider(server, new GenerationParameters(
                Optional.empty(),
                Optional.of(0.7),
                Optional.of(96)
            ));
            CustomOpenAiCompatibleLanguageModel languageModel = new CustomOpenAiCompatibleLanguageModel(
                provider,
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                credentials
            );
            WorldmindAcceptanceScenario scenario = WorldmindTestkit.scenario(languageModel);

            var outcome = scenario.submit(chatBatch(), configuration(provider), new ProviderCapabilities(true));

            assertFalse(outcome.toCompletableFuture().isDone());
            FakeOpenAiCompatibleHttpServer.CapturedRequest captured = server.awaitRequest(ASYNC_TIMEOUT);
            assertEquals("POST", captured.method());
            assertEquals("/v1/chat/completions", captured.requestUri().getPath());
            assertEquals("contract=wire", captured.requestUri().getQuery());
            assertEquals("application/json", captured.contentType());
            assertEquals("application/json", captured.accept());
            assertTrue(captured.authorizationPresent());
            assertTrue(captured.authorizationMatchesExpected());
            assertEquals(0, scenario.serverScheduler().pendingTaskCount());

            JsonObject body = JsonParser.parseString(captured.body()).getAsJsonObject();
            assertEquals("transport-model", body.get("model").getAsString());
            assertEquals(0.7, body.get("top_p").getAsDouble());
            assertEquals(96, body.get("max_tokens").getAsInt());
            assertFalse(body.has("temperature"));
            assertFalse(body.has("stream"));
            assertFalse(body.has("tools"));

            JsonArray messages = body.getAsJsonArray("messages");
            assertEquals(2, messages.size());
            JsonObject system = messages.get(0).getAsJsonObject();
            JsonObject user = messages.get(1).getAsJsonObject();
            assertEquals("system", system.get("role").getAsString());
            assertEquals("user", user.get("role").getAsString());
            String systemContent = system.get("content").getAsString();
            String userContent = user.get("content").getAsString();
            assertLayersInOrder(
                systemContent,
                "BUILT_IN_SAFETY_POLICY",
                "ADMINISTRATOR_RULES",
                "PERSONA"
            );
            assertLayersInOrder(
                userContent,
                "LORE",
                "MEMORY",
                "CURRENT_GAME_CONTEXT",
                "CURRENT_CHAT_BATCH"
            );
            assertTrue(systemContent.contains("profile.administrator-rules"));
            assertTrue(systemContent.contains("profile.persona"));
            assertTrue(userContent.contains("lore/settlement.md"));
            assertTrue(userContent.contains("vanilla-game-context"));
            assertTrue(userContent.contains("<worldmind-empty/>"));
            assertFalse(systemContent.contains("lore/settlement.md"));
            assertFalse(systemContent.contains("Where can I find shelter?"));
            assertFalse(userContent.contains("profile.administrator-rules"));
            assertFalse(userContent.contains("profile.persona"));
            assertFalse(captured.body().contains(PLAYER_ID.toString()));
            assertFalse(captured.body().contains(WORLD_ID.stableId()));
            assertFalse(captured.body().contains("env:WORLDMIND_TEST_KEY"));

            server.releaseResponses();
            scenario.serverScheduler().awaitPendingTask(ASYNC_TIMEOUT);
            assertFalse(outcome.toCompletableFuture().isDone());
            scenario.serverScheduler().runUntilIdle();

            DirectReply response = assertInstanceOf(DirectReply.class, outcome.toCompletableFuture().join());
            assertEquals("The observatory is quiet.", response.text());
        }
    }

    @Test
    void mapsBlankAndInvalidSuccessPayloadsToTypedConversationFailures() throws Exception {
        assertConversationRefusal("{\"choices\":[{\"message\":{\"content\":\"  \"}}]}", RefusalCode.EMPTY_RESPONSE);
        assertConversationRefusal("not-json", RefusalCode.INVALID_PROVIDER_RESPONSE);
        assertConversationRefusal("{\"choices\":[]}", RefusalCode.INVALID_PROVIDER_RESPONSE);
        assertConversationRefusal("{\"choices\":[{\"message\":{\"content\":7}}]}", RefusalCode.INVALID_PROVIDER_RESPONSE);
    }

    @Test
    void mapsNonSuccessAndUnavailableCredentialsWithoutLeakingTransportFailures() throws Exception {
        try (FakeOpenAiCompatibleHttpServer server = new FakeOpenAiCompatibleHttpServer()) {
            SyntheticCredentialResolver credentials = SyntheticCredentialResolver.available();
            server.expectBearerCredential(credentials.materialForFakeServer());
            server.respondWith(429, "{\"error\":\"unavailable\"}");
            ProviderConfiguration provider = provider(
                server,
                new GenerationParameters(Optional.of(0.3), Optional.empty(), Optional.empty())
            );
            WorldmindAcceptanceScenario scenario = WorldmindTestkit.scenario(new CustomOpenAiCompatibleLanguageModel(
                provider,
                HttpClient.newHttpClient(),
                credentials
            ));
            var outcome = submit(scenario, configuration(provider));
            server.awaitRequest(ASYNC_TIMEOUT);
            scenario.serverScheduler().awaitPendingTask(ASYNC_TIMEOUT);
            scenario.serverScheduler().runUntilIdle();

            ConversationRefusal refusal = assertInstanceOf(ConversationRefusal.class, outcome.toCompletableFuture().join());
            assertEquals(RefusalCode.PROVIDER_UNAVAILABLE, refusal.code());
        }

        try (FakeOpenAiCompatibleHttpServer server = new FakeOpenAiCompatibleHttpServer()) {
            ProviderConfiguration provider = provider(server, new GenerationParameters(Optional.empty(), Optional.empty(), Optional.empty()));
            WorldmindAcceptanceScenario scenario = WorldmindTestkit.scenario(new CustomOpenAiCompatibleLanguageModel(
                provider,
                HttpClient.newHttpClient(),
                SyntheticCredentialResolver.missing()
            ));
            var outcome = submit(scenario, configuration(provider));
            scenario.serverScheduler().awaitPendingTask(ASYNC_TIMEOUT);
            scenario.serverScheduler().runUntilIdle();

            ConversationRefusal refusal = assertInstanceOf(ConversationRefusal.class, outcome.toCompletableFuture().join());
            assertEquals(RefusalCode.PROVIDER_UNAVAILABLE, refusal.code());
            assertFalse(server.hasReceivedRequest());
        }
    }

    private void assertConversationRefusal(String providerResponse, RefusalCode expected) throws Exception {
        try (FakeOpenAiCompatibleHttpServer server = new FakeOpenAiCompatibleHttpServer()) {
            SyntheticCredentialResolver credentials = SyntheticCredentialResolver.available();
            server.expectBearerCredential(credentials.materialForFakeServer());
            server.respondWith(200, providerResponse);
            ProviderConfiguration provider = provider(server, new GenerationParameters(Optional.of(0.3), Optional.empty(), Optional.empty()));
            WorldmindAcceptanceScenario scenario = WorldmindTestkit.scenario(new CustomOpenAiCompatibleLanguageModel(
                provider,
                HttpClient.newHttpClient(),
                credentials
            ));

            var outcome = submit(scenario, configuration(provider));
            server.awaitRequest(ASYNC_TIMEOUT);
            scenario.serverScheduler().awaitPendingTask(ASYNC_TIMEOUT);
            scenario.serverScheduler().runUntilIdle();

            ConversationRefusal refusal = assertInstanceOf(ConversationRefusal.class, outcome.toCompletableFuture().join());
            assertEquals(expected, refusal.code());
        }
    }

    private java.util.concurrent.CompletionStage<ConversationOutcome> submit(
        WorldmindAcceptanceScenario scenario,
        ValidatedWorldmindConfiguration configuration
    ) {
        return scenario.submit(chatBatch(), configuration, new ProviderCapabilities(true));
    }

    private ProviderConfiguration provider(FakeOpenAiCompatibleHttpServer server, GenerationParameters generation) {
        return new ProviderConfiguration(
            CustomOpenAiCompatibleLanguageModel.PROVIDER_ID,
            new ProviderEndpoint(server.endpoint("/v1/chat/completions?contract=wire")),
            "transport-model",
            generation,
            new ExternalSecretReference("env:WORLDMIND_TEST_KEY")
        );
    }

    private ValidatedWorldmindConfiguration configuration(ProviderConfiguration provider) {
        return new ValidatedWorldmindConfiguration(
            new WorldmindGlobalConfiguration(
                WorldmindGlobalConfiguration.V1_SCHEMA_VERSION,
                true,
                "transport-profile",
                provider,
                new ChatBatchingConfiguration(8, 5_000, 4_000),
                new RequestQueueConfiguration(16, 2)
            ),
            new WorldmindProfile(
                WorldmindProfile.V1_SCHEMA_VERSION,
                "Aster",
                "A calm observer.",
                "Never claim server authority.",
                List.of(new LoreMaterial("lore/settlement.md", "The settlement is built around a quiet observatory.")),
                "brief and calm",
                new ResponseLengthLimit(180)
            )
        );
    }

    private String successfulResponse(String content) {
        return "{\"choices\":[{\"message\":{\"content\":\"" + content
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            + "\"}}]}";
    }

    private SealedChatBatch chatBatch() {
        return new SealedChatBatch(
            WORLD_ID,
            List.of(new ObservedPublicChatMessage(
                1,
                new ServerRequester(PLAYER_ID, "Mira"),
                "Where can I find shelter?",
                AddressingSignal.LIKELY,
                Instant.EPOCH,
                List.of()
            )),
            ChatBatchSealReason.ADDRESSING_SIGNAL,
            List.of(new UntrustedContext(
                "vanilla-game-context",
                "worldName=neutral-world; dimension=minecraft:overworld; gameTime=6000; weather=rain"
            ))
        );
    }

    private void assertLayersInOrder(String content, String... layerNames) {
        int previousIndex = -1;
        for (String layerName : layerNames) {
            int currentIndex = content.indexOf("type=\"" + layerName + "\"");
            assertTrue(currentIndex > previousIndex, "Expected layer order to contain " + layerName + ".");
            previousIndex = currentIndex;
        }
    }

    private static final class SyntheticCredentialResolver implements ProviderCredentialResolver {
        private final Optional<String> material;

        private SyntheticCredentialResolver(Optional<String> material) {
            this.material = material;
        }

        static SyntheticCredentialResolver available() {
            return new SyntheticCredentialResolver(Optional.of(UUID.randomUUID().toString()));
        }

        static SyntheticCredentialResolver missing() {
            return new SyntheticCredentialResolver(Optional.empty());
        }

        String materialForFakeServer() {
            return material.orElseThrow();
        }

        @Override
        public SecretAvailability check(ExternalSecretReference reference) {
            return material.isPresent() ? SecretAvailability.AVAILABLE : SecretAvailability.MISSING;
        }

        @Override
        public Optional<String> resolveForOutgoingRequest(ExternalSecretReference reference) {
            return material;
        }
    }
}
