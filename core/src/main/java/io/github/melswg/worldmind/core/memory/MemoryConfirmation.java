package io.github.melswg.worldmind.core.memory;

import java.time.Instant;
import java.util.Objects;

/** Immutable record of the first trusted confirmation of one memory record. */
public record MemoryConfirmation(
    MemoryConfirmationAuthority authority,
    String authorityIdentifier,
    Instant confirmedAt
) {
    public MemoryConfirmation {
        Objects.requireNonNull(authority, "authority");
        Objects.requireNonNull(authorityIdentifier, "authorityIdentifier");
        if (authorityIdentifier.isBlank()) {
            throw new IllegalArgumentException("authorityIdentifier must not be blank.");
        }
        Objects.requireNonNull(confirmedAt, "confirmedAt");
    }
}
