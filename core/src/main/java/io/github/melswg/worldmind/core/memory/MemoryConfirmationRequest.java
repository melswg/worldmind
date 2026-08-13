package io.github.melswg.worldmind.core.memory;

import java.util.Objects;

/** Caller-supplied, persisted explanation of a non-model confirmation action. */
public record MemoryConfirmationRequest(MemoryConfirmationAuthority authority, String authorityIdentifier) {
    public MemoryConfirmationRequest {
        Objects.requireNonNull(authority, "authority");
        Objects.requireNonNull(authorityIdentifier, "authorityIdentifier");
        if (authorityIdentifier.isBlank()) {
            throw new IllegalArgumentException("authorityIdentifier must not be blank.");
        }
    }
}
