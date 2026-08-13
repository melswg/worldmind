package io.github.melswg.worldmind.core.conversation;

/** Redaction-safe bounded-work accounting visible to acceptance tests and diagnostics. */
public record AsyncWorkSnapshot(int queued, int inFlight, boolean closed) {
    public AsyncWorkSnapshot {
        if (queued < 0 || inFlight < 0) throw new IllegalArgumentException("work counts must not be negative.");
    }
}
