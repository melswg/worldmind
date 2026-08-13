package io.github.melswg.worldmind.core.configuration;

import java.util.Objects;
import java.util.Optional;

/** Validated provider selection and non-secret generation settings. */
public record ProviderConfiguration(
    String providerId,
    Optional<ProviderEndpoint> endpoint,
    String model,
    GenerationParameters generationParameters,
    ExternalSecretReference secretReference,
    ProviderTimeoutConfiguration timeouts,
    ProviderRetryConfiguration retry,
    ProviderCircuitBreakerConfiguration circuitBreaker
) {
    public ProviderConfiguration {
        providerId = requireText(providerId, "providerId");
        endpoint = Objects.requireNonNull(endpoint, "endpoint");
        model = requireText(model, "model");
        Objects.requireNonNull(generationParameters, "generationParameters");
        Objects.requireNonNull(secretReference, "secretReference");
        Objects.requireNonNull(timeouts, "timeouts");
        Objects.requireNonNull(retry, "retry");
        Objects.requireNonNull(circuitBreaker, "circuitBreaker");
    }

    /** Compatibility constructor for programmatic integrations predating configurable timeouts. */
    public ProviderConfiguration(
        String providerId,
        ProviderEndpoint endpoint,
        String model,
        GenerationParameters generationParameters,
        ExternalSecretReference secretReference
    ) {
        this(providerId, Optional.of(Objects.requireNonNull(endpoint, "endpoint")), model, generationParameters, secretReference, ProviderTimeoutConfiguration.DEFAULT, ProviderRetryConfiguration.DEFAULT, ProviderCircuitBreakerConfiguration.DEFAULT);
    }

    public ProviderConfiguration(String providerId, ProviderEndpoint endpoint, String model, GenerationParameters generationParameters,
                                 ExternalSecretReference secretReference, ProviderTimeoutConfiguration timeouts) {
        this(providerId, Optional.of(Objects.requireNonNull(endpoint, "endpoint")), model, generationParameters, secretReference, timeouts, ProviderRetryConfiguration.DEFAULT, ProviderCircuitBreakerConfiguration.DEFAULT);
    }

    public ProviderConfiguration(String providerId, ProviderEndpoint endpoint, String model, GenerationParameters generationParameters,
                                 ExternalSecretReference secretReference, ProviderTimeoutConfiguration timeouts, ProviderRetryConfiguration retry) {
        this(providerId, Optional.of(Objects.requireNonNull(endpoint, "endpoint")), model, generationParameters, secretReference, timeouts, retry, ProviderCircuitBreakerConfiguration.DEFAULT);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }
}
