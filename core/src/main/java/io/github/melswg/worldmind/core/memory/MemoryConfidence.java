package io.github.melswg.worldmind.core.memory;

/** Bounded confidence assigned to an untrusted memory candidate. */
public record MemoryConfidence(double value) {
    public MemoryConfidence {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException("confidence must be between 0.0 and 1.0.");
        }
    }
}
