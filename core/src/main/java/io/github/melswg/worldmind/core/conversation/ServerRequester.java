package io.github.melswg.worldmind.core.conversation;

import java.util.Objects;
import java.util.UUID;

/** Stable server-side identity for later world and player scoped scenarios. */
public record ServerRequester(UUID playerId, String playerName) {
    public ServerRequester {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(playerName, "playerName");
        if (playerName.isBlank()) {
            throw new IllegalArgumentException("playerName must not be blank.");
        }
    }
}
