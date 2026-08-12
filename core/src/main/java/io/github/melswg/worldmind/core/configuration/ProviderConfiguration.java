package io.github.melswg.worldmind.core.configuration;

import java.util.Objects;

/** Validated provider selection and non-secret generation settings. */
public record ProviderConfiguration(
    String providerId,
    ProviderEndpoint endpoint,
    String model,
    GenerationParameters generationParameters,
    ExternalSecretReference secretReference
) {
    public ProviderConfiguration {
        providerId = requireText(providerId, "providerId");
        Objects.requireNonNull(endpoint, "endpoint");
        model = requireText(model, "model");
        Objects.requireNonNull(generationParameters, "generationParameters");
        Objects.requireNonNull(secretReference, "secretReference");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }
}
