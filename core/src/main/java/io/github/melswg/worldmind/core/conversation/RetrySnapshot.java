package io.github.melswg.worldmind.core.conversation;

/** Safe count of retry-owned active attempts and delayed backoff callbacks. */
public record RetrySnapshot(int activeAttempts, int waitingBackoff) {
    public RetrySnapshot {
        if (activeAttempts < 0 || waitingBackoff < 0) {
            throw new IllegalArgumentException("Retry counts must not be negative.");
        }
    }
}
