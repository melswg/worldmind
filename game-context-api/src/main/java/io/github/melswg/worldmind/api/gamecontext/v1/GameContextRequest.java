package io.github.melswg.worldmind.api.gamecontext.v1;

import java.time.Instant;
import java.util.Objects;

/**
 * Minimum sealed-batch metadata available to a context provider. It contains
 * no chat body, player identity, Minecraft object, configuration, memory, or
 * credential reference.
 */
public record GameContextRequest(
    GameContextServerContext server,
    String worldId,
    long firstSequence,
    long lastSequence,
    int messageCount,
    Instant requestedAt
) {
    public GameContextRequest {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(worldId, "worldId");
        if (worldId.isBlank()) throw new IllegalArgumentException("worldId must not be blank.");
        if (firstSequence <= 0 || lastSequence < firstSequence) {
            throw new IllegalArgumentException("sequence range must be positive and ordered.");
        }
        if (messageCount <= 0) throw new IllegalArgumentException("messageCount must be positive.");
        Objects.requireNonNull(requestedAt, "requestedAt");
    }
}
