package io.github.melswg.worldmind.fabric.provider;

import io.github.melswg.worldmind.core.configuration.ExternalSecretReference;
import io.github.melswg.worldmind.core.configuration.SecretResolver;
import java.util.Optional;

/**
 * Transport-side access to credential material. Core only receives the
 * availability half of this boundary through {@link SecretResolver}.
 */
public interface ProviderCredentialResolver extends SecretResolver {
    /**
     * Returns material solely for building an outgoing authorization header.
     * Callers must not retain, log, or expose the returned value.
     */
    Optional<String> resolveForOutgoingRequest(ExternalSecretReference reference);
}
