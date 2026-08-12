package io.github.melswg.worldmind.core.conversation;

import java.util.List;
import java.util.Objects;

/**
 * Provider-visible normalized content, kept independent of HTTP and JSON.
 */
public record ProviderRequest(String message, List<UntrustedContext> untrustedContext) {
    public ProviderRequest {
        Objects.requireNonNull(message, "message");
        if (message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank.");
        }
        untrustedContext = List.copyOf(Objects.requireNonNull(untrustedContext, "untrustedContext"));
    }
}
