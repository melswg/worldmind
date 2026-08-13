package io.github.melswg.worldmind.core.configuration;

/** Bounded retry policy; maximumAttempts includes the first HTTP attempt. */
public record ProviderRetryConfiguration(
    int maximumAttempts,
    long initialBackoffMillis,
    long maximumBackoffMillis,
    double jitterRatio
) {
    public static final ProviderRetryConfiguration DEFAULT = new ProviderRetryConfiguration(3, 250, 4_000, 0.2);

    public ProviderRetryConfiguration {
        if (maximumAttempts < 1 || maximumAttempts > 10) throw new IllegalArgumentException("maximumAttempts must be 1..10.");
        if (initialBackoffMillis < 1 || initialBackoffMillis > 60_000) throw new IllegalArgumentException("initialBackoffMillis must be 1..60000.");
        if (maximumBackoffMillis < initialBackoffMillis || maximumBackoffMillis > 300_000) {
            throw new IllegalArgumentException("maximumBackoffMillis must be at least initialBackoffMillis and at most 300000.");
        }
        if (!Double.isFinite(jitterRatio) || jitterRatio < 0.0 || jitterRatio > 0.5) {
            throw new IllegalArgumentException("jitterRatio must be 0.0..0.5.");
        }
    }
}
