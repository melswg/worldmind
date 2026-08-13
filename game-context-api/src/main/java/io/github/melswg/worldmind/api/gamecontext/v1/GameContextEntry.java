package io.github.melswg.worldmind.api.gamecontext.v1;

import java.util.Objects;

/** One structured context value. Runtime normalization and limits are applied by Worldmind. */
public record GameContextEntry(String key, String value) {
    public GameContextEntry {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
    }
}
