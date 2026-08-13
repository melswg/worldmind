package io.github.melswg.worldmind.core.configuration;

/** Bounded provider outage protection. */
public record ProviderCircuitBreakerConfiguration(int failureThreshold, long cooldownMillis) {
    public static final ProviderCircuitBreakerConfiguration DEFAULT = new ProviderCircuitBreakerConfiguration(5, 30_000);

    public ProviderCircuitBreakerConfiguration {
        if (failureThreshold < 1 || failureThreshold > 100) throw new IllegalArgumentException("failureThreshold must be 1..100.");
        if (cooldownMillis < 1 || cooldownMillis > 3_600_000) throw new IllegalArgumentException("cooldownMillis must be 1..3600000.");
    }
}
