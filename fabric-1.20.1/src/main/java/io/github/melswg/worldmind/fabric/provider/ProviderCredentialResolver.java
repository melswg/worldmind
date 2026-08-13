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
    Optional<ProviderCredential> resolveForOutgoingRequest(ExternalSecretReference reference);

    /**
     * A typed variation of the transport-only lookup. Existing resolvers keep
     * their compatibility method; production can preserve missing versus
     * unreadable material without exposing it beyond this boundary.
     */
    default ProviderCredentialResolution resolveForOutgoingRequestResult(ExternalSecretReference reference) {
        return resolveForOutgoingRequest(reference)
            .<ProviderCredentialResolution>map(value -> new ProviderCredentialResolution(
                io.github.melswg.worldmind.core.configuration.SecretAvailability.AVAILABLE,
                Optional.of(value)
            ))
            .orElseGet(() -> ProviderCredentialResolution.unavailable(
                io.github.melswg.worldmind.core.configuration.SecretAvailability.MISSING
            ));
    }
}
