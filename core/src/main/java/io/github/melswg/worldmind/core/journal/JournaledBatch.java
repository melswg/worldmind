package io.github.melswg.worldmind.core.journal;

import io.github.melswg.worldmind.core.conversation.ChatBatchSealReason;
import io.github.melswg.worldmind.core.conversation.SealedChatBatch;
import io.github.melswg.worldmind.core.conversation.WorldIdentity;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Persisted membership of one sealed, ordered chat batch. */
public record JournaledBatch(
    UUID batchId,
    WorldIdentity worldIdentity,
    List<Long> messageSequences,
    ChatBatchSealReason sealReason,
    Instant sealedAt
) {
    public JournaledBatch {
        Objects.requireNonNull(batchId, "batchId");
        Objects.requireNonNull(worldIdentity, "worldIdentity");
        messageSequences = List.copyOf(Objects.requireNonNull(messageSequences, "messageSequences"));
        if (messageSequences.isEmpty()) throw new IllegalArgumentException("messageSequences must not be empty.");
        long previous = 0;
        for (Long sequence : messageSequences) {
            if (sequence == null || sequence <= previous) throw new IllegalArgumentException("message sequences must be strictly increasing.");
            previous = sequence;
        }
        Objects.requireNonNull(sealReason, "sealReason");
        Objects.requireNonNull(sealedAt, "sealedAt");
    }

    public long firstSequence() { return messageSequences.get(0); }
    public long lastSequence() { return messageSequences.get(messageSequences.size() - 1); }

    public static JournaledBatch from(SealedChatBatch batch, Instant sealedAt) {
        return new JournaledBatch(UUID.randomUUID(), batch.worldIdentity(), batch.messages().stream().map(message -> message.sequence()).toList(), batch.sealReason(), sealedAt);
    }
}
