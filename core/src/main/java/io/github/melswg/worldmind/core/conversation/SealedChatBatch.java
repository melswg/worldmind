package io.github.melswg.worldmind.core.conversation;

import java.util.List;
import java.util.Objects;

/**
 * Ordered non-empty unit handed from batching to the future batch consumer.
 * Its context snapshot is the source-attributed context copied with the last
 * message, so selection is deterministic and reflects the newest observation.
 */
public record SealedChatBatch(
    WorldIdentity worldIdentity,
    List<ObservedPublicChatMessage> messages,
    ChatBatchSealReason sealReason,
    List<UntrustedContext> currentContextSnapshot
) {
    public SealedChatBatch {
        Objects.requireNonNull(worldIdentity, "worldIdentity");
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty.");
        }
        assertStrictlyOrdered(messages);
        Objects.requireNonNull(sealReason, "sealReason");
        currentContextSnapshot = List.copyOf(Objects.requireNonNull(currentContextSnapshot, "currentContextSnapshot"));
    }

    private static void assertStrictlyOrdered(List<ObservedPublicChatMessage> messages) {
        long previous = 0;
        for (ObservedPublicChatMessage message : messages) {
            Objects.requireNonNull(message, "messages must not contain null");
            if (message.sequence() <= previous) {
                throw new IllegalArgumentException("messages must be in strictly increasing sequence order.");
            }
            previous = message.sequence();
        }
    }
}
