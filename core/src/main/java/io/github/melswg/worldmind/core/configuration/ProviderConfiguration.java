package io.github.melswg.worldmind.core.configuration;

import java.util.Objects;

/** Validated provider selection and non-secret generation settings. */
public record ProviderConfiguration(
    String providerId,
    ProviderEndpoint endpoint,
    String model,
    GenerationParameters generationParameters,
    ExternalSecretReference secretReference,
    ProviderTimeoutConfiguration timeouts
) {
    public ProviderConfiguration {
        providerId = requireText(providerId, "providerId");
        Objects.requireNonNull(endpoint, "endpoint");
        model = requireText(model, "model");
        Objects.requireNonNull(generationParameters, "generationParameters");
        Objects.requireNonNull(secretReference, "secretReference");
        Objects.requireNonNull(timeouts, "timeouts");
    }

    /** Compatibility constructor for programmatic integrations predating configurable timeouts. */
    public ProviderConfiguration(
        String providerId,
        ProviderEndpoint endpoint,
        String model,
        GenerationParameters generationParameters,
        ExternalSecretReference secretReference
    ) {
        this(providerId, endpoint, model, generationParameters, secretReference, ProviderTimeoutConfiguration.DEFAULT);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }
}
