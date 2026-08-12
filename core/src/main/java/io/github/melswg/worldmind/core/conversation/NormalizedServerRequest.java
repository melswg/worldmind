package io.github.melswg.worldmind.core.conversation;

import io.github.melswg.worldmind.core.configuration.ValidatedWorldmindConfiguration;
import java.util.List;
import java.util.Objects;

/**
 * A request normalized by the server adapter before core assembles the
 * provider-neutral conversation from validated configuration and data inputs.
 */
public record NormalizedServerRequest(
    ServerRequester requester,
    WorldIdentity worldIdentity,
    String message,
    List<UntrustedContext> currentGameContext,
    ValidatedWorldmindConfiguration validatedConfiguration,
    ProviderCapabilities providerCapabilities
) {
    public NormalizedServerRequest {
        Objects.requireNonNull(requester, "requester");
        Objects.requireNonNull(worldIdentity, "worldIdentity");
        message = requireText(message, "message");
        currentGameContext = List.copyOf(Objects.requireNonNull(currentGameContext, "currentGameContext"));
        Objects.requireNonNull(validatedConfiguration, "validatedConfiguration");
        Objects.requireNonNull(providerCapabilities, "providerCapabilities");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }
}
