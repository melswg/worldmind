package io.github.melswg.worldmind.core.journal;

import io.github.melswg.worldmind.core.conversation.AddressingSignal;
import io.github.melswg.worldmind.core.conversation.ObservedPublicChatMessage;
import io.github.melswg.worldmind.core.conversation.ServerRequester;
import io.github.melswg.worldmind.core.conversation.WorldIdentity;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** One append-only raw public-chat observation with a database-assigned sequence. */
public record JournaledObservation(
    WorldIdentity worldIdentity,
    long sequence,
    ServerRequester requester,
    String text,
    Instant capturedAt,
    JournalMessageSource source,
    JournalVisibility visibility,
    AddressingSignal addressingSignal
) {
    public JournaledObservation {
        Objects.requireNonNull(worldIdentity, "worldIdentity");
        if (sequence <= 0) throw new IllegalArgumentException("sequence must be positive.");
        Objects.requireNonNull(requester, "requester");
        if (Objects.requireNonNull(text, "text").isBlank()) throw new IllegalArgumentException("text must not be blank.");
        Objects.requireNonNull(capturedAt, "capturedAt");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(visibility, "visibility");
        Objects.requireNonNull(addressingSignal, "addressingSignal");
    }

    /** Adds the per-event context snapshot after the durable raw observation has been assigned its sequence. */
    public ObservedPublicChatMessage toObservedPublicChatMessage(List<io.github.melswg.worldmind.core.conversation.UntrustedContext> currentContext) {
        return new ObservedPublicChatMessage(sequence, requester, text, addressingSignal, capturedAt, currentContext);
    }
}
