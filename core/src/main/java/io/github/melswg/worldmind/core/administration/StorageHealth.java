package io.github.melswg.worldmind.core.administration;

/** Redaction-safe storage state for the world-owned journal and memory. */
public enum StorageHealth {
    OPENING,
    BACKING_UP,
    MIGRATING,
    VERIFYING,
    READY,
    DEGRADED,
    FAILED,
    CLOSING,
    CLOSED
}
