package io.github.melswg.worldmind.core.administration;

import java.util.Objects;

/** Safe accounting for best-effort compaction; no source dialogue is exposed. */
public record CompactionStatus(int queued, int inFlight, String lastOutcome) {
    public CompactionStatus {
        if (queued < 0 || inFlight < 0) {
            throw new IllegalArgumentException("Compaction counts must not be negative.");
        }
        lastOutcome = Objects.requireNonNull(lastOutcome, "lastOutcome");
        if (lastOutcome.isBlank()) {
            throw new IllegalArgumentException("lastOutcome must not be blank.");
        }
    }
}
