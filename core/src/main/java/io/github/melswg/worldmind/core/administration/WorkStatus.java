package io.github.melswg.worldmind.core.administration;

/** Bounded queue and retry/backoff counts. */
public record WorkStatus(int queued, int inFlight, boolean closed, int retryAttempts, int waitingBackoff) {
    public WorkStatus {
        if (queued < 0 || inFlight < 0 || retryAttempts < 0 || waitingBackoff < 0) {
            throw new IllegalArgumentException("Work counts must not be negative.");
        }
    }
}
