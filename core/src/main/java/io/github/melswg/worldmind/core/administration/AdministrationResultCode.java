package io.github.melswg.worldmind.core.administration;

/** Stable, redaction-safe result categories for Worldmind operator operations. */
public enum AdministrationResultCode {
    SUCCESS,
    ACCEPTED,
    NO_CHANGE,
    VALIDATION_FAILED,
    INVALID_CANDIDATE,
    UNSUPPORTED_CHANGE,
    RELOAD_IN_PROGRESS,
    EXPORT_IN_PROGRESS,
    LIFECYCLE_NOT_READY,
    STORAGE_NOT_READY,
    STORAGE_UNAVAILABLE,
    INVALID_CURSOR,
    INVALID_RECORD_ID,
    NOT_FOUND,
    CANCELLED,
    IO_FAILURE,
    UNSAFE_EXPORT_PATH
}
