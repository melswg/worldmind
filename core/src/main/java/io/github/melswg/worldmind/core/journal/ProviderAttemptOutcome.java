package io.github.melswg.worldmind.core.journal;

/** Audit category for whether and how a provider attempt completed. */
public enum ProviderAttemptOutcome {
    SUCCEEDED,
    FAILED,
    NOT_ATTEMPTED,
    CANCELLED
}
