package io.github.melswg.worldmind.core.conversation;

import io.github.melswg.worldmind.core.configuration.ValidatedWorldmindConfiguration;
import java.util.Objects;

/**
 * A request normalized by the server adapter before core assembles the
 * provider-neutral conversation from validated configuration and data inputs.
 */
public record NormalizedServerRequest(
    SealedChatBatch chatBatch,
    ValidatedWorldmindConfiguration validatedConfiguration,
    ProviderCapabilities providerCapabilities
) {
    public NormalizedServerRequest {
        Objects.requireNonNull(chatBatch, "chatBatch");
        Objects.requireNonNull(validatedConfiguration, "validatedConfiguration");
        Objects.requireNonNull(providerCapabilities, "providerCapabilities");
    }
}
