package io.github.melswg.worldmind.core.administration;

/** Provider availability without any endpoint, model, credential, or request data. */
public enum ProviderAvailability {
    READY,
    DISABLED,
    SECRET_MISSING,
    SECRET_UNREADABLE,
    CREDENTIAL_REJECTED,
    AUTHENTICATION_FAILED,
    CIRCUIT_BLOCKED,
    NOT_READY
}
