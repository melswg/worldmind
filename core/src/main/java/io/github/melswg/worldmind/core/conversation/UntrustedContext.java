package io.github.melswg.worldmind.core.conversation;

import java.util.Objects;

/** Context data that has not been granted instruction authority. */
public record UntrustedContext(String source, String content) {
    public UntrustedContext {
        source = requireText(source, "source");
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
