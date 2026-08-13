package io.github.melswg.worldmind.core.configuration;

/** Finite transport limits for one provider attempt. */
public record ProviderTimeoutConfiguration(long connectMillis, long responseCompletionMillis) {
    public static final ProviderTimeoutConfiguration DEFAULT = new ProviderTimeoutConfiguration(5_000, 30_000);

    public ProviderTimeoutConfiguration {
        if (connectMillis <= 0 || connectMillis > 60_000) {
            throw new IllegalArgumentException("connectMillis must be between 1 and 60000.");
        }
        if (responseCompletionMillis < connectMillis || responseCompletionMillis > 300_000) {
            throw new IllegalArgumentException("responseCompletionMillis must be at least connectMillis and at most 300000.");
        }
    }
}
