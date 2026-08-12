package io.github.melswg.worldmind.core.conversation;

import java.util.Objects;

/** A contextually selected reply to a direct address in the current chat batch. */
public record DirectReply(String text) implements ConversationOutcome {
    public DirectReply {
        Objects.requireNonNull(text, "text");
        if (text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank.");
        }
    }
}
