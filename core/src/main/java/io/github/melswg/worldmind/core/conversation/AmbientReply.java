package io.github.melswg.worldmind.core.conversation;

import java.util.Objects;

/** A contextually selected contribution that is not necessarily a direct address. */
public record AmbientReply(String text) implements ConversationOutcome {
    public AmbientReply {
        Objects.requireNonNull(text, "text");
        if (text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank.");
        }
    }
}
