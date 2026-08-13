package io.github.melswg.worldmind.core.administration;

import java.util.Objects;

/** Content-free result for one bounded retention page. */
public record RetentionSweepResult(AdministrationResultCode code, int expiredObservations, boolean moreRemaining) {
    public RetentionSweepResult {
        Objects.requireNonNull(code, "code");
        if (expiredObservations < 0) throw new IllegalArgumentException("expiredObservations must not be negative.");
    }

    public static RetentionSweepResult idle() { return new RetentionSweepResult(AdministrationResultCode.NO_CHANGE, 0, false); }
}
