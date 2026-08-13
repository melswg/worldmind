package io.github.melswg.worldmind.core.configuration;

/** Finite global limits for owned asynchronous conversation and compaction work. */
public record RequestQueueConfiguration(int capacity, int maxConcurrency) {
    public RequestQueueConfiguration {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive.");
        if (maxConcurrency <= 0) throw new IllegalArgumentException("maxConcurrency must be positive.");
    }
}
