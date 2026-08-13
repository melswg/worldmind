package io.github.melswg.worldmind.api.gamecontext.v1;

import java.util.Objects;

/** Opaque logical-server session identity; it is not a Minecraft server handle. */
public record GameContextServerContext(String sessionId) {
    public GameContextServerContext {
        Objects.requireNonNull(sessionId, "sessionId");
        if (sessionId.isBlank()) throw new IllegalArgumentException("sessionId must not be blank.");
    }
}
