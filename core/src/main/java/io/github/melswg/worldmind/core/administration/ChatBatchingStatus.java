package io.github.melswg.worldmind.core.administration;

/** Validated limits and bounded pending-batch accounting. */
public record ChatBatchingStatus(
    int maxMessages,
    int maxWaitMillis,
    int maxEstimatedInputCharacters,
    int pendingMessages,
    int pendingBatches
) {
    public ChatBatchingStatus {
        if (maxMessages < 0 || maxWaitMillis < 0 || maxEstimatedInputCharacters < 0
            || pendingMessages < 0 || pendingBatches < 0) {
            throw new IllegalArgumentException("Status counts must not be negative.");
        }
    }
}
