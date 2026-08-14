package io.github.melswg.worldmind.fabric.provider;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melswg.worldmind.core.administration.ProviderAvailability;
import io.github.melswg.worldmind.core.configuration.ExternalSecretReference;
import io.github.melswg.worldmind.core.configuration.GenerationParameters;
import io.github.melswg.worldmind.core.configuration.ProviderCircuitBreakerConfiguration;
import io.github.melswg.worldmind.core.configuration.ProviderConfiguration;
import io.github.melswg.worldmind.core.configuration.ProviderEndpoint;
import io.github.melswg.worldmind.core.configuration.ProviderRetryConfiguration;
import io.github.melswg.worldmind.core.configuration.ProviderTimeoutConfiguration;
import io.github.melswg.worldmind.core.conversation.PromptFragment;
import io.github.melswg.worldmind.core.conversation.PromptLayer;
import io.github.melswg.worldmind.core.conversation.PromptLayerType;
import io.github.melswg.worldmind.core.conversation.PromptTrust;
import io.github.melswg.worldmind.core.conversation.ProviderRequest;
import io.github.melswg.worldmind.testkit.FakeOpenAiCompatibleHttpServer;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ProviderTransportCanaryTest {
    @Test
    void actualLoopbackTransportUsesCanaryOnlyInAuthorizationAndNeverInBody() throws Exception {
        String canary = "wm-canary-" + UUID.randomUUID();
        try (FakeOpenAiCompatibleHttpServer server = new FakeOpenAiCompatibleHttpServer()) {
            server.expectBearerCredential(canary);
            ProviderConfiguration configuration = new ProviderConfiguration(
                ProviderPresetDescriptor.CUSTOM, Optional.of(new ProviderEndpoint(server.endpoint("/v1/chat/completions"))),
                "release-audit-model", new GenerationParameters(Optional.of(0.2), Optional.empty(), Optional.of(64)),
                new ExternalSecretReference("env:WORLDMIND_RELEASE_CANARY"), ProviderTimeoutConfiguration.DEFAULT,
                ProviderRetryConfiguration.DEFAULT, ProviderCircuitBreakerConfiguration.DEFAULT
            );
            ChatCompletionsLanguageModel model = new ChatCompletionsLanguageModel(configuration,
                BuiltInProviderPresetRegistry.standard().descriptor(ProviderPresetDescriptor.CUSTOM),
                server.endpoint("/v1/chat/completions"), HttpClient.newHttpClient(),
                new EnvironmentProviderCredentialResolver(ignored -> canary), new AtomicReference<>(ProviderAvailability.READY));
            server.enqueueResponse(200, "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"SILENT\"}}]}", java.util.Map.of());

            model.complete(new ProviderRequest("release-audit-model", configuration.generationParameters(), List.of(
                new PromptLayer(PromptLayerType.BUILT_IN_SAFETY_POLICY, PromptTrust.TRUSTED_INSTRUCTION,
                    List.of(new PromptFragment("policy", "Synthetic safe policy.")))
            ))).toCompletableFuture().get(5, TimeUnit.SECONDS);

            FakeOpenAiCompatibleHttpServer.CapturedRequest request = server.awaitNextRequest(Duration.ofSeconds(5));
            assertTrue(request.authorizationPresent());
            assertTrue(request.authorizationMatchesExpected());
            assertFalse(request.body().contains(canary));
            assertFalse(request.body().contains("WORLDMIND_RELEASE_CANARY"));
        }
    }
}
