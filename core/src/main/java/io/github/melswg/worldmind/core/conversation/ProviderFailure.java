package io.github.melswg.worldmind.core.conversation;

import java.util.Objects;
import java.util.Optional;
import java.time.Duration;

/** A provider failure deliberately stripped of response bodies, headers, and exception text. */
public record ProviderFailure(ProviderFailureKind kind, Optional<Duration> retryAfter) implements LanguageModelResult {
    public ProviderFailure {
        Objects.requireNonNull(kind, "kind");
        retryAfter = Objects.requireNonNull(retryAfter, "retryAfter");
        retryAfter.ifPresent(value -> {
            if (value.isNegative() || value.isZero()) throw new IllegalArgumentException("retryAfter must be positive.");
        });
    }

    public ProviderFailure(ProviderFailureKind kind) {
        this(kind, Optional.empty());
    }
}
