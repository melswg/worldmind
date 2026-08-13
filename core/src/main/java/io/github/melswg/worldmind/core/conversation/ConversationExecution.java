package io.github.melswg.worldmind.core.conversation;

import java.util.Objects;

/** Outcome plus whether the language-model boundary was actually invoked. */
public record ConversationExecution(ConversationOutcome outcome, boolean providerAttempted) {
    public ConversationExecution {
        Objects.requireNonNull(outcome, "outcome");
    }
}
