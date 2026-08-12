package io.github.melswg.worldmind.core.configuration;

import java.util.Objects;

/**
 * An opaque pointer to externally managed provider credentials. It never holds
 * the credential value itself.
 */
public record ExternalSecretReference(String reference) {
    public ExternalSecretReference {
        Objects.requireNonNull(reference, "reference");
        if (reference.isBlank()) {
            throw new IllegalArgumentException("reference must not be blank.");
        }
    }
}
