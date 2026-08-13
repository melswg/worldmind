package io.github.melswg.worldmind.core.conversation;

/** Safe bounded accounting for the pending part of a chat batch coordinator. */
public record ChatBatchSnapshot(int pendingMessages, int pendingBatches, boolean closed) {
    public ChatBatchSnapshot {
        if (pendingMessages < 0 || pendingBatches < 0) {
            throw new IllegalArgumentException("Batch counts must not be negative.");
        }
    }
}
