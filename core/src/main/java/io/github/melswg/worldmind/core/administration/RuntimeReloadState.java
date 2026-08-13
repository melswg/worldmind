package io.github.melswg.worldmind.core.administration;

/** Observable state of the single-flight configuration reload operation. */
public enum RuntimeReloadState {
    IDLE,
    VALIDATING,
    PREPARING,
    RETIRING_OLD,
    ACTIVATING_NEW
}
