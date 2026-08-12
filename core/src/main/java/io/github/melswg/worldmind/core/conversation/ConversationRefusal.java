package io.github.melswg.worldmind.core.conversation;

import java.util.Objects;

/** A controlled refusal whose reason can be observed without parsing text. */
public record ConversationRefusal(RefusalCode code) implements ConversationOutcome {
    public ConversationRefusal {
        Objects.requireNonNull(code, "code");
    }
}
