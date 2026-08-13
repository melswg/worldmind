package io.github.melswg.worldmind.fabric.provider;

import io.github.melswg.worldmind.core.configuration.SecretAvailability;
import java.util.Objects;
import java.util.Optional;

/** Credential material is available only for constructing one authorization header. */
public record ProviderCredentialResolution(SecretAvailability availability, Optional<ProviderCredential> credential) {
    public ProviderCredentialResolution {
        Objects.requireNonNull(availability, "availability");
        credential = Objects.requireNonNull(credential, "credential");
        if ((availability == SecretAvailability.AVAILABLE) != credential.isPresent()) {
            throw new IllegalArgumentException("Available credentials must carry material and unavailable credentials must not.");
        }
    }

    public static ProviderCredentialResolution unavailable(SecretAvailability availability) {
        if (availability == SecretAvailability.AVAILABLE) throw new IllegalArgumentException("availability must be unavailable.");
        return new ProviderCredentialResolution(availability, Optional.empty());
    }
}
