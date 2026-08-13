package io.github.melswg.worldmind.core.memory;

import io.github.melswg.worldmind.core.conversation.SealedChatBatch;
import java.util.Objects;

/** Current sealed public-chat batch used for scoped local memory retrieval. */
public record MemoryRetrievalRequest(SealedChatBatch chatBatch) {
    public MemoryRetrievalRequest {
        Objects.requireNonNull(chatBatch, "chatBatch");
    }
}
