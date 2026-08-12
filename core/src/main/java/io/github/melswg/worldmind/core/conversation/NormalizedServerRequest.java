package io.github.melswg.worldmind.core.conversation;

import java.util.List;
import java.util.Objects;

/**
 * A request already normalized by the server adapter. It deliberately carries
 * data only; profile loading and production prompt assembly are outside this seam.
 */
public record NormalizedServerRequest(
    ServerRequester requester,
    String message,
    List<UntrustedContext> untrustedContext
) {
    public NormalizedServerRequest {
        Objects.requireNonNull(requester, "requester");
        message = requireText(message, "message");
        untrustedContext = List.copyOf(Objects.requireNonNull(untrustedContext, "untrustedContext"));
    }

    public ProviderRequest providerRequest() {
        return new ProviderRequest(message, untrustedContext);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }
}
