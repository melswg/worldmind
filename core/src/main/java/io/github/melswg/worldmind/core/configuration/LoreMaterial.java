package io.github.melswg.worldmind.core.configuration;

import java.util.Objects;

/** A named, untrusted lore input loaded from a portable profile. */
public record LoreMaterial(String name, String content) {
    public LoreMaterial {
        name = requireText(name, "name");
        content = requireText(content, "content");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }
}
