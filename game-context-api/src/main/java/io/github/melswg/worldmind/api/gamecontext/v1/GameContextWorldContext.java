package io.github.melswg.worldmind.api.gamecontext.v1;

import java.util.Objects;

/** Opaque loaded-world lifecycle data with no live Minecraft-world reference. */
public record GameContextWorldContext(GameContextServerContext server, String dimensionId) {
    public GameContextWorldContext {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(dimensionId, "dimensionId");
        if (dimensionId.isBlank()) throw new IllegalArgumentException("dimensionId must not be blank.");
    }
}
