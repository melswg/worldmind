package io.github.melswg.worldmind.fabric.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.melswg.worldmind.core.configuration.ChatBatchingConfiguration;
import io.github.melswg.worldmind.core.configuration.ChatNameColor;
import io.github.melswg.worldmind.core.configuration.DialogueRetentionConfiguration;
import io.github.melswg.worldmind.core.configuration.ExternalSecretReference;
import io.github.melswg.worldmind.core.configuration.GenerationParameters;
import io.github.melswg.worldmind.core.configuration.LoreMaterial;
import io.github.melswg.worldmind.core.configuration.ProviderCircuitBreakerConfiguration;
import io.github.melswg.worldmind.core.configuration.ProviderConfiguration;
import io.github.melswg.worldmind.core.configuration.ProviderEndpoint;
import io.github.melswg.worldmind.core.configuration.ProviderRetryConfiguration;
import io.github.melswg.worldmind.core.configuration.ProviderTimeoutConfiguration;
import io.github.melswg.worldmind.core.configuration.RequestQueueConfiguration;
import io.github.melswg.worldmind.core.configuration.ResponseLengthLimit;
import io.github.melswg.worldmind.core.configuration.SecretAvailability;
import io.github.melswg.worldmind.core.configuration.ValidatedWorldmindConfiguration;
import io.github.melswg.worldmind.core.configuration.WorldmindGlobalConfiguration;
import io.github.melswg.worldmind.core.configuration.WorldmindProfile;
import io.github.melswg.worldmind.core.conversation.AddressingSignal;
import io.github.melswg.worldmind.core.conversation.AmbientReply;
import io.github.melswg.worldmind.core.conversation.ChatBatchSealReason;
import io.github.melswg.worldmind.core.conversation.ConversationOutcome;
import io.github.melswg.worldmind.core.conversation.ConversationRefusal;
import io.github.melswg.worldmind.core.conversation.DeliberateSilence;
import io.github.melswg.worldmind.core.conversation.DirectReply;
import io.github.melswg.worldmind.core.conversation.LanguageModelResult;
import io.github.melswg.worldmind.core.conversation.ObservedPublicChatMessage;
import io.github.melswg.worldmind.core.conversation.ProviderFailure;
import io.github.melswg.worldmind.core.conversation.ProviderFailureKind;
import io.github.melswg.worldmind.core.conversation.ProviderRefusal;
import io.github.melswg.worldmind.core.conversation.ProviderRequest;
import io.github.melswg.worldmind.core.conversation.RefusalCode;
import io.github.melswg.worldmind.core.conversation.ServerRequester;
import io.github.melswg.worldmind.core.conversation.SealedChatBatch;
import io.github.melswg.worldmind.core.conversation.UntrustedContext;
import io.github.melswg.worldmind.core.conversation.WorldIdentity;
import io.github.melswg.worldmind.testkit.FakeOpenAiCompatibleHttpServer;
import io.github.melswg.worldmind.testkit.WorldmindAcceptanceScenario;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/** One provider-neutral conversation contract executed against each transport descriptor. */
class ProviderContractSuiteTest {
    private static final Duration ASYNC_TIMEOUT = Duration.ofSeconds(5);

    @TestFactory
    Stream<DynamicTest> threePresetContracts() {
        return Stream.of(ProviderFixture.values()).map(fixture -> DynamicTest.dynamicTest(
            fixture.id + " provider contract", () -> verifyConversationAndTransportContract(fixture)
        ));
    }

    @TestFactory
    Stream<DynamicTest> threePresetFailureContracts() {
        return Stream.of(ProviderFixture.values()).map(fixture -> DynamicTest.dynamicTest(
            fixture.id + " failure/redaction contract", () -> verifyFailureContract(fixture)
        ));
    }

    @TestFactory
    Stream<DynamicTest> threePresetCancellationAndBodyLimitContracts() {
        return Stream.of(ProviderFixture.values()).flatMap(fixture -> Stream.of(
            DynamicTest.dynamicTest(fixture.id + " cancellation contract", () -> verifyCancellationContract(fixture)),
            DynamicTest.dynamicTest(fixture.id + " bounded-response contract", () -> verifyBoundedResponseContract(fixture)),
            DynamicTest.dynamicTest(fixture.id + " connection-failure contract", () -> verifyConnectionFailureContract(fixture)),
            DynamicTest.dynamicTest(fixture.id + " response-timeout contract", () -> verifyResponseTimeoutContract(fixture))
        ));
    }

    private void verifyConversationAndTransportContract(ProviderFixture fixture) throws Exception {
        try (FakeOpenAiCompatibleHttpServer server = new FakeOpenAiCompatibleHttpServer()) {
            String canary = UUID.randomUUID().toString();
            server.expectBearerCredential(canary);
            ChatCompletionsLanguageModel model = fixture.model(server, canary);

            assertInstanceOf(DirectReply.class, submit(server, model, fixture, "DIRECT_REPLY\nClear skies.", AddressingSignal.EXACT));
            assertInstanceOf(AmbientReply.class, submit(server, model, fixture, "AMBIENT_REPLY\nThe wind is turning.", AddressingSignal.LIKELY));
            assertEquals(DeliberateSilence.INSTANCE, submit(server, model, fixture, "SILENT", AddressingSignal.LIKELY));
            ConversationRefusal malformed = assertInstanceOf(ConversationRefusal.class,
                submit(server, model, fixture, "unstructured provider output", AddressingSignal.EXACT));
            assertEquals(RefusalCode.INVALID_PROVIDER_RESPONSE, malformed.code());

            FakeOpenAiCompatibleHttpServer.CapturedRequest captured = server.awaitNextRequest(ASYNC_TIMEOUT);
            JsonObject body = JsonParser.parseString(captured.body()).getAsJsonObject();
            assertEquals(fixture.model, body.get("model").getAsString());
            assertEquals(2, body.getAsJsonArray("messages").size());
            assertEquals("system", body.getAsJsonArray("messages").get(0).getAsJsonObject().get("role").getAsString());
            assertEquals("user", body.getAsJsonArray("messages").get(1).getAsJsonObject().get("role").getAsString());
            String trusted = body.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString();
            String untrusted = body.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString();
            assertTrue(trusted.contains("BUILT_IN_SAFETY_POLICY"));
            assertTrue(trusted.contains("ADMINISTRATOR_RULES"));
            assertTrue(trusted.contains("PERSONA"));
            assertTrue(untrusted.contains("LORE"));
            assertTrue(untrusted.contains("CURRENT_GAME_CONTEXT"));
            assertTrue(untrusted.contains("CURRENT_CHAT_BATCH"));
            assertFalse(trusted.contains("CURRENT_CHAT_BATCH"));
            assertFalse(untrusted.contains("ADMINISTRATOR_RULES"));
            assertTrue(captured.authorizationPresent());
            assertTrue(captured.authorizationMatchesExpected());
            assertFalse(captured.body().contains(canary));
            assertFalse(captured.body().contains("env:WORLD_CONTRACT_KEY"));
            assertFalse(captured.headerNames().contains("http-referer"));
            assertFalse(captured.headerNames().contains("x-openrouter-title"));
            assertFalse(captured.headerNames().contains("x-openrouter-metadata"));
            fixture.assertRequestEnvelope(body);
        }
    }

    private void verifyFailureContract(ProviderFixture fixture) throws Exception {
        try (FakeOpenAiCompatibleHttpServer server = new FakeOpenAiCompatibleHttpServer()) {
            String canary = UUID.randomUUID().toString();
            server.expectBearerCredential(canary);
            ChatCompletionsLanguageModel model = fixture.model(server, canary);

            server.enqueueResponse(401, "{\"error\":{\"code\":401,\"message\":\"synthetic\"}}", java.util.Map.of());
            assertEquals(ProviderFailureKind.HTTP_AUTHENTICATION, failure(model, fixture).kind());
            server.enqueueResponse(429, "{\"error\":{\"code\":429,\"message\":\"synthetic\"}}", java.util.Map.of());
            assertEquals(ProviderFailureKind.HTTP_RATE_LIMITED, failure(model, fixture).kind());
            server.enqueueResponse(503, "{\"error\":{\"code\":503,\"message\":\"synthetic\"}}", java.util.Map.of());
            assertEquals(ProviderFailureKind.HTTP_SERVER_ERROR, failure(model, fixture).kind());
            server.enqueueResponse(200, "not-json", java.util.Map.of());
            assertEquals(ProviderFailureKind.MALFORMED_JSON, failure(model, fixture).kind());
            server.enqueueResponse(200, "{\"choices\":[{\"finish_reason\":\"content_filter\",\"message\":{\"role\":\"assistant\",\"content\":null}}]}", java.util.Map.of());
            LanguageModelResult refusalOrMalformed = model.complete(providerRequest(fixture)).toCompletableFuture().get();
            if (fixture == ProviderFixture.CUSTOM) {
                assertEquals(ProviderFailureKind.MALFORMED_JSON,
                    assertInstanceOf(ProviderFailure.class, refusalOrMalformed).kind());
            } else {
                ProviderRefusal refusal = assertInstanceOf(ProviderRefusal.class, refusalOrMalformed);
                assertEquals(RefusalCode.PROVIDER_REFUSED, refusal.code());
            }
        }
    }

    private void verifyCancellationContract(ProviderFixture fixture) throws Exception {
        try (FakeOpenAiCompatibleHttpServer server = new FakeOpenAiCompatibleHttpServer()) {
            String canary = UUID.randomUUID().toString();
            server.expectBearerCredential(canary);
            server.holdResponses();
            ChatCompletionsLanguageModel model = fixture.model(server, canary);

            var pending = model.complete(providerRequest(fixture)).toCompletableFuture();
            server.awaitRequest(ASYNC_TIMEOUT);
            assertTrue(pending.cancel(true));
            assertTrue(pending.isCancelled());
            server.releaseResponses();
        }
    }

    private void verifyBoundedResponseContract(ProviderFixture fixture) throws Exception {
        try (FakeOpenAiCompatibleHttpServer server = new FakeOpenAiCompatibleHttpServer()) {
            String canary = UUID.randomUUID().toString();
            server.expectBearerCredential(canary);
            server.respondWith(200, "x".repeat(262_145));
            ChatCompletionsLanguageModel model = fixture.model(server, canary);

            ProviderFailure failure = assertInstanceOf(ProviderFailure.class,
                model.complete(providerRequest(fixture)).toCompletableFuture().get());
            assertEquals(ProviderFailureKind.OVERSIZED_CONTENT, failure.kind());
        }
    }

    private void verifyConnectionFailureContract(ProviderFixture fixture) throws Exception {
        try (FakeOpenAiCompatibleHttpServer server = new FakeOpenAiCompatibleHttpServer()) {
            String canary = UUID.randomUUID().toString();
            server.expectBearerCredential(canary);
            server.enqueueConnectionClose();
            ProviderFailure failure = assertInstanceOf(ProviderFailure.class,
                fixture.model(server, canary).complete(providerRequest(fixture)).toCompletableFuture().get());
            assertEquals(ProviderFailureKind.CONNECTION_FAILURE, failure.kind());
        }
    }

    private void verifyResponseTimeoutContract(ProviderFixture fixture) throws Exception {
        try (FakeOpenAiCompatibleHttpServer server = new FakeOpenAiCompatibleHttpServer()) {
            String canary = UUID.randomUUID().toString();
            server.expectBearerCredential(canary);
            server.holdResponses();
            ChatCompletionsLanguageModel model = fixture.model(server, canary, new ProviderTimeoutConfiguration(100, 100));
            ProviderFailure failure = assertInstanceOf(ProviderFailure.class,
                model.complete(providerRequest(fixture)).toCompletableFuture().get(ASYNC_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS));
            assertEquals(ProviderFailureKind.TIMEOUT, failure.kind());
        }
    }

    private ConversationOutcome submit(
        FakeOpenAiCompatibleHttpServer server,
        ChatCompletionsLanguageModel model,
        ProviderFixture fixture,
        String response,
        AddressingSignal signal
    ) throws Exception {
        server.enqueueResponse(200, success(response), java.util.Map.of());
        WorldmindAcceptanceScenario scenario = new WorldmindAcceptanceScenario(model);
        var outcome = scenario.submit(batch(signal), configuration(fixture.configuration(server)), fixture.descriptor().capabilities());
        server.awaitRequest(ASYNC_TIMEOUT);
        scenario.serverScheduler().awaitPendingTask(ASYNC_TIMEOUT);
        scenario.serverScheduler().runUntilIdle();
        return outcome.toCompletableFuture().get();
    }

    private ProviderFailure failure(ChatCompletionsLanguageModel model, ProviderFixture fixture) throws Exception {
        LanguageModelResult result = model.complete(providerRequest(fixture)).toCompletableFuture().get();
        return assertInstanceOf(ProviderFailure.class, result);
    }

    private static ProviderRequest providerRequest(ProviderFixture fixture) {
        return new io.github.melswg.worldmind.core.conversation.ProviderRequest(fixture.model,
            new GenerationParameters(Optional.of(0.3), Optional.empty(), Optional.of(120)), List.of(
                new io.github.melswg.worldmind.core.conversation.PromptLayer(
                    io.github.melswg.worldmind.core.conversation.PromptLayerType.BUILT_IN_SAFETY_POLICY,
                    io.github.melswg.worldmind.core.conversation.PromptTrust.TRUSTED_INSTRUCTION,
                    List.of(new io.github.melswg.worldmind.core.conversation.PromptFragment("policy", "Protocol only."))
                ),
                new io.github.melswg.worldmind.core.conversation.PromptLayer(
                    io.github.melswg.worldmind.core.conversation.PromptLayerType.CURRENT_CHAT_BATCH,
                    io.github.melswg.worldmind.core.conversation.PromptTrust.UNTRUSTED_DATA,
                    List.of(new io.github.melswg.worldmind.core.conversation.PromptFragment("chat", "hello"))
                )
            ));
    }

    private static SealedChatBatch batch(AddressingSignal signal) {
        return new SealedChatBatch(new WorldIdentity("contract-world"), List.of(new ObservedPublicChatMessage(1,
            new ServerRequester(UUID.fromString("7d4cb4c0-063a-4e71-b8f8-8a9de813bcde"), "Mira"), "Aster, are you there?",
            signal, Instant.EPOCH, List.of())), ChatBatchSealReason.ADDRESSING_SIGNAL,
            List.of(new UntrustedContext("context", "world=contract")));
    }

    private static ValidatedWorldmindConfiguration configuration(ProviderConfiguration provider) {
        return new ValidatedWorldmindConfiguration(new WorldmindGlobalConfiguration(WorldmindGlobalConfiguration.V3_SCHEMA_VERSION,
            true, "contract", provider, new ChatBatchingConfiguration(8, 5_000, 4_000), new RequestQueueConfiguration(16, 2),
            DialogueRetentionConfiguration.legacyDefaults()), new WorldmindProfile(WorldmindProfile.V1_SCHEMA_VERSION, "Aster",
            "calm observer", "never claim authority", List.of(new LoreMaterial("lore/world.md", "quiet observatory")), "calm",
            new ResponseLengthLimit(180), ChatNameColor.LIGHT_PURPLE));
    }

    private static String success(String value) {
        return "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\""
            + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"}}]}";
    }

    private enum ProviderFixture {
        CUSTOM(ProviderPresetDescriptor.CUSTOM, "custom-model"),
        OPENROUTER(ProviderPresetDescriptor.OPENROUTER, "openai/gpt-4"),
        DEEPSEEK(ProviderPresetDescriptor.DEEPSEEK_DIRECT, "deepseek-v4-flash");

        private final String id;
        private final String model;

        ProviderFixture(String id, String model) { this.id = id; this.model = model; }

        ProviderPresetDescriptor descriptor() { return BuiltInProviderPresetRegistry.standard().descriptor(id); }

        ProviderConfiguration configuration(FakeOpenAiCompatibleHttpServer server) {
            Optional<ProviderEndpoint> endpoint = this == CUSTOM ? Optional.of(new ProviderEndpoint(server.endpoint("/v1/chat/completions"))) : Optional.empty();
            return new ProviderConfiguration(id, endpoint, model, new GenerationParameters(Optional.of(0.3), Optional.empty(), Optional.of(120)),
                new ExternalSecretReference("env:WORLD_CONTRACT_KEY"), ProviderTimeoutConfiguration.DEFAULT,
                ProviderRetryConfiguration.DEFAULT, ProviderCircuitBreakerConfiguration.DEFAULT);
        }

        ChatCompletionsLanguageModel model(FakeOpenAiCompatibleHttpServer server, String canary) {
            return model(server, canary, ProviderTimeoutConfiguration.DEFAULT);
        }

        ChatCompletionsLanguageModel model(
            FakeOpenAiCompatibleHttpServer server,
            String canary,
            ProviderTimeoutConfiguration timeouts
        ) {
            AtomicReference<io.github.melswg.worldmind.core.administration.ProviderAvailability> availability = new AtomicReference<>(
                io.github.melswg.worldmind.core.administration.ProviderAvailability.READY);
            ProviderConfiguration configuration = configuration(server);
            configuration = new ProviderConfiguration(configuration.providerId(), configuration.endpoint(), configuration.model(),
                configuration.generationParameters(), configuration.secretReference(), timeouts, configuration.retry(), configuration.circuitBreaker());
            return new ChatCompletionsLanguageModel(configuration, descriptor(), server.endpoint("/test-chat-completions"),
                HttpClient.newHttpClient(), new FixedCredential(canary), availability);
        }

        void assertRequestEnvelope(JsonObject body) {
            if (this == OPENROUTER) {
                assertTrue(body.has("max_completion_tokens"));
                assertEquals(false, body.get("stream").getAsBoolean());
                assertFalse(body.has("max_tokens"));
            } else if (this == DEEPSEEK) {
                assertTrue(body.has("max_tokens"));
                assertEquals(false, body.get("stream").getAsBoolean());
                assertEquals("disabled", body.getAsJsonObject("thinking").get("type").getAsString());
            } else {
                assertTrue(body.has("max_tokens"));
                assertFalse(body.has("stream"));
            }
        }
    }

    private record FixedCredential(String value) implements ProviderCredentialResolver {
        @Override public SecretAvailability check(ExternalSecretReference reference) { return SecretAvailability.AVAILABLE; }
        @Override public Optional<ProviderCredential> resolveForOutgoingRequest(ExternalSecretReference reference) {
            return Optional.of(new ProviderCredential(value));
        }
    }
}
