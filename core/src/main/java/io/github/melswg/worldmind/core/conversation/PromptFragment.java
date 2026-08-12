package io.github.melswg.worldmind.core.conversation;

import java.util.Objects;

/** A source-attributed part of a single prompt layer. */
public record PromptFragment(String source, String content) {
    public PromptFragment {
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
