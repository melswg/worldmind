package io.github.melswg.worldmind.core.conversation;

import java.util.Objects;

/**
 * Stable server-side identity for a world. This value is orchestration metadata
 * and is deliberately distinct from any player-visible world name.
 */
public record WorldIdentity(String stableId) {
    public WorldIdentity {
        Objects.requireNonNull(stableId, "stableId");
        if (stableId.isBlank()) {
            throw new IllegalArgumentException("stableId must not be blank.");
        }
    }
}
