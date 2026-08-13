package io.github.melswg.worldmind.fabric.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import io.github.melswg.worldmind.core.conversation.ProviderRequest;
import io.github.melswg.worldmind.core.conversation.ProviderResponse;
import io.github.melswg.worldmind.testkit.FakeOpenAiCompatibleHttpServer;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class BuiltInProviderPresetRegistryTest {
    @Test
    void rejectsDuplicateIdsAndUnknownIdsWithoutRetainingOperatorInput() {
        assertThrows(IllegalStateException.class, () -> new BuiltInProviderPresetRegistry(List.of(
            ProviderPresetDescriptor.custom(), ProviderPresetDescriptor.custom()
        )));

        var unknown = BuiltInProviderPresetRegistry.standard().validate("not-a-preset", Optional.empty(), "model", generation());
        assertEquals(BuiltInProviderPresetRegistry.ProviderPresetValidation.Kind.UNKNOWN, unknown.kind());
        assertTrueEmpty(unknown.reason());
    }

    @Test
    void mapsOpenRouterWithoutAttributionOrMetadataHeaders() throws Exception {
        try (FakeOpenAiCompatibleHttpServer server = new FakeOpenAiCompatibleHttpServer()) {
            String canary = UUID.randomUUID().toString();
            server.expectBearerCredential(canary);
            server.respondWith(200, "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"DIRECT_REPLY\\nready\"}}]}");
            ProviderPresetDescriptor descriptor = BuiltInProviderPresetRegistry.standard().descriptor(ProviderPresetDescriptor.OPENROUTER);
            ProviderConfiguration configuration = new ProviderConfiguration(
                ProviderPresetDescriptor.OPENROUTER, Optional.empty(), "openai/gpt-4", generation(),
                new ExternalSecretReference("env:WORLD_TEST"), ProviderTimeoutConfiguration.DEFAULT,
                ProviderRetryConfiguration.DEFAULT, ProviderCircuitBreakerConfiguration.DEFAULT
            );
            ChatCompletionsLanguageModel model = new ChatCompletionsLanguageModel(configuration, descriptor,
                server.endpoint("/chat/completions"), HttpClient.newHttpClient(), new FixedCredentialResolver(canary),
                new AtomicReference<>(io.github.melswg.worldmind.core.administration.ProviderAvailability.READY));

            LanguageModelResult result = model.complete(request()).toCompletableFuture().get();
            ProviderResponse response = assertInstanceOf(ProviderResponse.class, result);
            assertEquals("DIRECT_REPLY\nready", response.text());
            var captured = server.awaitRequest(Duration.ofSeconds(5));
            JsonObject request = JsonParser.parseString(captured.body()).getAsJsonObject();
            assertEquals("openai/gpt-4", request.get("model").getAsString());
            assertEquals(120, request.get("max_completion_tokens").getAsInt());
            assertFalse(request.has("max_tokens"));
            assertFalse(request.has("tools"));
            assertEquals(false, request.get("stream").getAsBoolean());
            assertEquals(2, request.getAsJsonArray("messages").size());
            assertFalse(captured.body().contains(canary));
            assertFalse(captured.body().contains("env:WORLD_TEST"));
        }
    }

    private static GenerationParameters generation() {
        return new GenerationParameters(Optional.of(0.3), Optional.empty(), Optional.of(120));
    }

    private static ProviderRequest request() {
        return new ProviderRequest("openai/gpt-4", generation(), List.of(
            new PromptLayer(PromptLayerType.BUILT_IN_SAFETY_POLICY, PromptTrust.TRUSTED_INSTRUCTION,
                List.of(new PromptFragment("policy", "Return only the Worldmind protocol."))),
            new PromptLayer(PromptLayerType.CURRENT_CHAT_BATCH, PromptTrust.UNTRUSTED_DATA,
                List.of(new PromptFragment("chat", "hello")))
        ));
    }

    private static void assertTrueEmpty(Optional<?> value) {
        if (value.isPresent()) throw new AssertionError("Expected no safe detail for unknown provider id.");
    }

    private record FixedCredentialResolver(String material) implements ProviderCredentialResolver {
        @Override public SecretAvailability check(ExternalSecretReference reference) { return SecretAvailability.AVAILABLE; }
        @Override public Optional<ProviderCredential> resolveForOutgoingRequest(ExternalSecretReference reference) {
            return Optional.of(new ProviderCredential(material));
        }
    }
}
