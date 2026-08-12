package io.github.melswg.worldmind.core.conversation;

import io.github.melswg.worldmind.core.configuration.GenerationParameters;
import java.util.List;
import java.util.Objects;

/**
 * Immutable provider-neutral conversation, kept independent of HTTP, JSON,
 * credentials, and server-only request metadata.
 */
public record ProviderRequest(
    String model,
    GenerationParameters generationParameters,
    List<PromptLayer> promptLayers
) {
    public ProviderRequest {
        Objects.requireNonNull(model, "model");
        if (model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank.");
        }
        Objects.requireNonNull(generationParameters, "generationParameters");
        promptLayers = List.copyOf(Objects.requireNonNull(promptLayers, "promptLayers"));
        if (promptLayers.isEmpty()) {
            throw new IllegalArgumentException("promptLayers must not be empty.");
        }
    }
}
