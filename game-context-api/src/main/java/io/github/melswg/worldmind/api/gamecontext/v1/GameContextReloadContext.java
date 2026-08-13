package io.github.melswg.worldmind.api.gamecontext.v1;

import java.util.Objects;

/** Monotonic logical-server generation supplied to a successful Worldmind reload. */
public record GameContextReloadContext(GameContextServerContext server, long generation) {
    public GameContextReloadContext {
        Objects.requireNonNull(server, "server");
        if (generation <= 0) throw new IllegalArgumentException("generation must be positive.");
    }
}
