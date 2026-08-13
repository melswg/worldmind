package io.github.melswg.worldmind.fabric.provider;

import io.github.melswg.worldmind.core.administration.ProviderAvailability;
import io.github.melswg.worldmind.core.configuration.GenerationParameters;
import io.github.melswg.worldmind.core.configuration.ProviderConfiguration;
import io.github.melswg.worldmind.core.configuration.ProviderEndpoint;
import io.github.melswg.worldmind.core.conversation.ProviderCapabilities;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Closed registry for v0.1 built-ins. It deliberately exposes resolution, not
 * registration, so application and core conversation code remain provider-neutral.
 */
public final class BuiltInProviderPresetRegistry {
    private static final BuiltInProviderPresetRegistry STANDARD = new BuiltInProviderPresetRegistry(List.of(
        ProviderPresetDescriptor.custom(), ProviderPresetDescriptor.openRouter(), ProviderPresetDescriptor.deepSeek()
    ));
    private final Map<String, ProviderPresetDescriptor> descriptors;

    public static BuiltInProviderPresetRegistry standard() {
        return STANDARD;
    }

    BuiltInProviderPresetRegistry(Collection<ProviderPresetDescriptor> descriptors) {
        Objects.requireNonNull(descriptors, "descriptors");
        Map<String, ProviderPresetDescriptor> ordered = new LinkedHashMap<>();
        for (ProviderPresetDescriptor descriptor : descriptors) {
            ProviderPresetDescriptor previous = ordered.putIfAbsent(Objects.requireNonNull(descriptor, "descriptor").id(), descriptor);
            if (previous != null) throw new IllegalStateException("Duplicate built-in provider preset id.");
        }
        this.descriptors = Map.copyOf(ordered);
    }

    /** Safe validation result; it carries no configuration/credential material. */
    public ProviderPresetValidation validate(
        String providerId,
        Optional<ProviderEndpoint> endpoint,
        String model,
        GenerationParameters generation
    ) {
        ProviderPresetDescriptor descriptor = descriptors.get(providerId);
        if (descriptor == null) return ProviderPresetValidation.unknown();
        try {
            Optional<String> problem = descriptor.validate(new ProviderConfiguration(
                providerId, endpoint, model, generation,
                new io.github.melswg.worldmind.core.configuration.ExternalSecretReference("env:VALIDATION_ONLY"),
                io.github.melswg.worldmind.core.configuration.ProviderTimeoutConfiguration.DEFAULT,
                io.github.melswg.worldmind.core.configuration.ProviderRetryConfiguration.DEFAULT,
                io.github.melswg.worldmind.core.configuration.ProviderCircuitBreakerConfiguration.DEFAULT
            ));
            return problem.map(ProviderPresetValidation::invalid).orElseGet(ProviderPresetValidation::valid);
        } catch (RuntimeException failure) {
            return ProviderPresetValidation.invalid("provider preset configuration is invalid.");
        }
    }

    public ProviderRuntimeHandle create(ProviderConfiguration configuration, ProviderCredentialResolver credentials) {
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(credentials, "credentials");
        ProviderPresetDescriptor descriptor = descriptors.get(configuration.providerId());
        if (descriptor == null) throw new IllegalArgumentException("Unknown built-in provider preset.");
        if (descriptor.validate(configuration).isPresent()) throw new IllegalArgumentException("Provider preset was not validated.");
        AtomicReference<ProviderAvailability> availability = new AtomicReference<>(ProviderAvailability.READY);
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofMillis(configuration.timeouts().connectMillis()))
            .build();
        return new ProviderRuntimeHandle(configuration.providerId(),
            new ChatCompletionsLanguageModel(configuration, descriptor, descriptor.resolveEndpoint(configuration), client, credentials, availability),
            descriptor.capabilities(), availability::get);
    }

    ProviderPresetDescriptor descriptor(String id) {
        return descriptors.get(id);
    }

    public record ProviderPresetValidation(Kind kind, Optional<String> reason) {
        public enum Kind { VALID, UNKNOWN, INVALID }
        public ProviderPresetValidation {
            Objects.requireNonNull(kind, "kind");
            reason = Objects.requireNonNull(reason, "reason");
        }
        static ProviderPresetValidation valid() { return new ProviderPresetValidation(Kind.VALID, Optional.empty()); }
        static ProviderPresetValidation unknown() { return new ProviderPresetValidation(Kind.UNKNOWN, Optional.empty()); }
        static ProviderPresetValidation invalid(String reason) { return new ProviderPresetValidation(Kind.INVALID, Optional.of(Objects.requireNonNull(reason, "reason"))); }
    }
}
