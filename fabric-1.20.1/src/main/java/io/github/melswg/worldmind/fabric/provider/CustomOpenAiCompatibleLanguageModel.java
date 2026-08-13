package io.github.melswg.worldmind.fabric.provider;

import io.github.melswg.worldmind.core.configuration.ProviderConfiguration;
import io.github.melswg.worldmind.core.conversation.LanguageModel;
import io.github.melswg.worldmind.core.conversation.LanguageModelResult;
import io.github.melswg.worldmind.core.conversation.ProviderRequest;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * OpenAI-compatible Chat Completions transport. It serializes the stable core
 * request but does not expose Minecraft types, credentials, or JSON to core.
 */
public final class CustomOpenAiCompatibleLanguageModel implements LanguageModel {
    public static final String PROVIDER_ID = "custom-openai-compatible";
    private final ChatCompletionsLanguageModel delegate;

    public static CustomOpenAiCompatibleLanguageModel create(
        ProviderConfiguration configuration,
        ProviderCredentialResolver credentials
    ) {
        return new CustomOpenAiCompatibleLanguageModel(configuration,
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofMillis(configuration.timeouts().connectMillis())).build(), credentials);
    }

    public CustomOpenAiCompatibleLanguageModel(
        ProviderConfiguration configuration,
        HttpClient httpClient,
        ProviderCredentialResolver credentials
    ) {
        Objects.requireNonNull(configuration, "configuration");
        if (!PROVIDER_ID.equals(configuration.providerId())) {
            throw new IllegalArgumentException("Custom adapter requires provider id " + PROVIDER_ID + ".");
        }
        ProviderPresetDescriptor descriptor = Objects.requireNonNull(BuiltInProviderPresetRegistry.standard().descriptor(PROVIDER_ID), "descriptor");
        this.delegate = new ChatCompletionsLanguageModel(configuration, descriptor, descriptor.resolveEndpoint(configuration),
            Objects.requireNonNull(httpClient, "httpClient"), Objects.requireNonNull(credentials, "credentials"),
            new AtomicReference<>(io.github.melswg.worldmind.core.administration.ProviderAvailability.READY));
    }

    @Override
    public CompletionStage<LanguageModelResult> complete(ProviderRequest providerRequest) {
        return delegate.complete(providerRequest);
    }
}
