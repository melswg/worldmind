package io.github.melswg.worldmind.fabric.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.melswg.worldmind.core.administration.ProviderAvailability;
import io.github.melswg.worldmind.core.configuration.ExternalSecretReference;
import io.github.melswg.worldmind.core.configuration.GenerationParameters;
import io.github.melswg.worldmind.core.configuration.ProviderCircuitBreakerConfiguration;
import io.github.melswg.worldmind.core.configuration.ProviderConfiguration;
import io.github.melswg.worldmind.core.configuration.ProviderRetryConfiguration;
import io.github.melswg.worldmind.core.configuration.ProviderTimeoutConfiguration;
import io.github.melswg.worldmind.core.configuration.SecretAvailability;
import io.github.melswg.worldmind.core.conversation.LanguageModelResult;
import io.github.melswg.worldmind.core.conversation.PromptFragment;
import io.github.melswg.worldmind.core.conversation.PromptLayer;
import io.github.melswg.worldmind.core.conversation.PromptLayerType;
import io.github.melswg.worldmind.core.conversation.PromptTrust;
import io.github.melswg.worldmind.core.conversation.ProviderFailure;
import io.github.melswg.worldmind.core.conversation.ProviderFailureKind;
import io.github.melswg.worldmind.core.conversation.ProviderRefusal;
import io.github.melswg.worldmind.core.conversation.ProviderRequest;
import io.github.melswg.worldmind.core.conversation.ProviderResponse;
import io.github.melswg.worldmind.core.conversation.RefusalCode;
import io.github.melswg.worldmind.testkit.FakeOpenAiCompatibleHttpServer;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DeepSeekProviderPresetTest {
    @Test
    void mapsDirectDeepSeekRequestAndProtocolResponseWithoutCredentialsInThePayload() throws Exception {
        try (FakeOpenAiCompatibleHttpServer server = new FakeOpenAiCompatibleHttpServer()) {
            String canary = UUID.randomUUID().toString();
            server.expectBearerCredential(canary);
            server.respondWith(200, success("DIRECT_REPLY\nThe lantern is lit."));
            AtomicReference<ProviderAvailability> availability = new AtomicReference<>(ProviderAvailability.READY);
            ChatCompletionsLanguageModel model = model(server, new AvailableCredentialResolver(canary), availability);

            ProviderResponse result = assertInstanceOf(ProviderResponse.class, model.complete(request()).toCompletableFuture().get());
            assertEquals("DIRECT_REPLY\nThe lantern is lit.", result.text());
            JsonObject payload = JsonParser.parseString(server.awaitRequest(Duration.ofSeconds(5)).body()).getAsJsonObject();
            assertEquals("deepseek-v4-flash", payload.get("model").getAsString());
            assertEquals(120, payload.get("max_tokens").getAsInt());
            assertEquals(false, payload.get("stream").getAsBoolean());
            assertEquals("disabled", payload.getAsJsonObject("thinking").get("type").getAsString());
            assertFalse(payload.has("max_completion_tokens"));
            assertFalse(payload.has("tools"));
            assertFalse(payload.toString().contains(canary));
            assertFalse(payload.toString().contains("env:WORLD_DEEPSEEK_KEY"));
            assertEquals(ProviderAvailability.READY, availability.get());
        }
    }

    @Test
    void mapsDirectDeepSeekFailuresAndMissingCredentialBeforeHttp() throws Exception {
        assertFailure(400, "{}", ProviderFailureKind.INCOMPATIBLE_MODEL_OR_PARAMETER);
        assertFailure(401, "{}", ProviderFailureKind.HTTP_AUTHENTICATION);
        assertFailure(429, "{}", ProviderFailureKind.HTTP_RATE_LIMITED);
        assertFailure(503, "{}", ProviderFailureKind.HTTP_SERVER_ERROR);
        assertResponse("{\"choices\":[{\"finish_reason\":\"content_filter\",\"message\":{\"role\":\"assistant\",\"content\":null}}]}", RefusalCode.PROVIDER_REFUSED);
        assertFailureResponse("{\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[]}}]}", ProviderFailureKind.MALFORMED_RESPONSE);

        try (FakeOpenAiCompatibleHttpServer server = new FakeOpenAiCompatibleHttpServer()) {
            AtomicReference<ProviderAvailability> availability = new AtomicReference<>(ProviderAvailability.READY);
            ChatCompletionsLanguageModel model = model(server, new MissingCredentialResolver(), availability);
            ProviderRefusal refusal = assertInstanceOf(ProviderRefusal.class, model.complete(request()).toCompletableFuture().get());
            assertEquals(RefusalCode.PROVIDER_UNAVAILABLE, refusal.code());
            assertEquals(ProviderAvailability.SECRET_MISSING, availability.get());
            assertFalse(server.hasReceivedRequest());
        }
    }

    @Test
    void acceptsOnlyOfficialDirectModelsAndTheDocumentedOutputBound() {
        var registry = BuiltInProviderPresetRegistry.standard();
        assertEquals(BuiltInProviderPresetRegistry.ProviderPresetValidation.Kind.VALID,
            registry.validate(ProviderPresetDescriptor.DEEPSEEK_DIRECT, Optional.empty(), "deepseek-v4-pro", generation()).kind());
        assertEquals(BuiltInProviderPresetRegistry.ProviderPresetValidation.Kind.INVALID,
            registry.validate(ProviderPresetDescriptor.DEEPSEEK_DIRECT, Optional.empty(), "deepseek-chat", generation()).kind());
        assertEquals(BuiltInProviderPresetRegistry.ProviderPresetValidation.Kind.INVALID,
            registry.validate(ProviderPresetDescriptor.DEEPSEEK_DIRECT, Optional.empty(), "deepseek-v4-flash",
                new GenerationParameters(Optional.empty(), Optional.empty(), Optional.of(393_217))).kind());
        assertEquals(BuiltInProviderPresetRegistry.ProviderPresetValidation.Kind.INVALID,
            registry.validate(ProviderPresetDescriptor.DEEPSEEK_DIRECT,
                Optional.of(new io.github.melswg.worldmind.core.configuration.ProviderEndpoint(java.net.URI.create("https://example.invalid"))),
                "deepseek-v4-flash", generation()).kind());
    }

    private static void assertFailure(int status, String body, ProviderFailureKind expected) throws Exception {
        try (FakeOpenAiCompatibleHttpServer server = new FakeOpenAiCompatibleHttpServer()) {
            server.respondWith(status, body);
            ProviderFailure failure = assertInstanceOf(ProviderFailure.class,
                model(server, new AvailableCredentialResolver(UUID.randomUUID().toString()), new AtomicReference<>(ProviderAvailability.READY))
                    .complete(request()).toCompletableFuture().get());
            assertEquals(expected, failure.kind());
        }
    }

    private static void assertFailureResponse(String body, ProviderFailureKind expected) throws Exception {
        try (FakeOpenAiCompatibleHttpServer server = new FakeOpenAiCompatibleHttpServer()) {
            server.respondWith(200, body);
            ProviderFailure failure = assertInstanceOf(ProviderFailure.class,
                model(server, new AvailableCredentialResolver(UUID.randomUUID().toString()), new AtomicReference<>(ProviderAvailability.READY))
                    .complete(request()).toCompletableFuture().get());
            assertEquals(expected, failure.kind());
        }
    }

    private static void assertResponse(String body, RefusalCode expected) throws Exception {
        try (FakeOpenAiCompatibleHttpServer server = new FakeOpenAiCompatibleHttpServer()) {
            server.respondWith(200, body);
            LanguageModelResult result = model(server, new AvailableCredentialResolver(UUID.randomUUID().toString()), new AtomicReference<>(ProviderAvailability.READY))
                .complete(request()).toCompletableFuture().get();
            ProviderRefusal refusal = assertInstanceOf(ProviderRefusal.class, result);
            assertEquals(expected, refusal.code());
        }
    }

    private static ChatCompletionsLanguageModel model(
        FakeOpenAiCompatibleHttpServer server,
        ProviderCredentialResolver credentials,
        AtomicReference<ProviderAvailability> availability
    ) {
        ProviderPresetDescriptor descriptor = BuiltInProviderPresetRegistry.standard().descriptor(ProviderPresetDescriptor.DEEPSEEK_DIRECT);
        ProviderConfiguration configuration = new ProviderConfiguration(ProviderPresetDescriptor.DEEPSEEK_DIRECT, Optional.empty(),
            "deepseek-v4-flash", generation(), new ExternalSecretReference("env:WORLD_DEEPSEEK_KEY"),
            ProviderTimeoutConfiguration.DEFAULT, ProviderRetryConfiguration.DEFAULT, ProviderCircuitBreakerConfiguration.DEFAULT);
        return new ChatCompletionsLanguageModel(configuration, descriptor, server.endpoint("/chat/completions"),
            HttpClient.newHttpClient(), credentials, availability);
    }

    private static GenerationParameters generation() {
        return new GenerationParameters(Optional.of(0.2), Optional.empty(), Optional.of(120));
    }

    private static ProviderRequest request() {
        return new ProviderRequest("deepseek-v4-flash", generation(), List.of(
            new PromptLayer(PromptLayerType.BUILT_IN_SAFETY_POLICY, PromptTrust.TRUSTED_INSTRUCTION,
                List.of(new PromptFragment("policy", "Worldmind protocol only."))),
            new PromptLayer(PromptLayerType.CURRENT_CHAT_BATCH, PromptTrust.UNTRUSTED_DATA,
                List.of(new PromptFragment("chat", "hello")))
        ));
    }

    private static String success(String value) {
        return "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\""
            + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"}}]}";
    }

    private record AvailableCredentialResolver(String material) implements ProviderCredentialResolver {
        @Override public SecretAvailability check(ExternalSecretReference reference) { return SecretAvailability.AVAILABLE; }
        @Override public Optional<ProviderCredential> resolveForOutgoingRequest(ExternalSecretReference reference) {
            return Optional.of(new ProviderCredential(material));
        }
    }

    private static final class MissingCredentialResolver implements ProviderCredentialResolver {
        @Override public SecretAvailability check(ExternalSecretReference reference) { return SecretAvailability.MISSING; }
        @Override public Optional<ProviderCredential> resolveForOutgoingRequest(ExternalSecretReference reference) { return Optional.empty(); }
    }
}
