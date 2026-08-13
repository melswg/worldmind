package io.github.melswg.worldmind.core.memory;

/** Exact inclusive raw-journal sequence range from which a memory record was derived. */
public record JournalSequenceRange(long firstSequence, long lastSequence) {
    public JournalSequenceRange {
        if (firstSequence <= 0) {
            throw new IllegalArgumentException("firstSequence must be positive.");
        }
        if (lastSequence < firstSequence) {
            throw new IllegalArgumentException("lastSequence must not precede firstSequence.");
        }
    }
}
