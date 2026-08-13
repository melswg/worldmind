package io.github.melswg.worldmind.core.memory;

/** Bounded importance signal retained for future local retrieval policies. */
public record MemoryImportance(double value) {
    public MemoryImportance {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException("importance must be between 0.0 and 1.0.");
        }
    }
}
