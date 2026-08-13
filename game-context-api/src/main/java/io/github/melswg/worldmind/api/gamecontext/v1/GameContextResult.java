package io.github.melswg.worldmind.api.gamecontext.v1;

import java.util.List;
import java.util.Objects;

/** Immutable provider result; an empty list intentionally contributes no context. */
public record GameContextResult(List<GameContextEntry> entries) {
    public GameContextResult {
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    }

    public static GameContextResult empty() {
        return new GameContextResult(List.of());
    }
}
