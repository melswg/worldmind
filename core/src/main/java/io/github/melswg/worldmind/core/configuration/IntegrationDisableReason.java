package io.github.melswg.worldmind.core.configuration;

/** Stable machine-readable reasons for keeping the LLM integration disabled. */
public enum IntegrationDisableReason {
    DISABLED_BY_OPERATOR,
    INVALID_CONFIGURATION,
    SECRET_UNAVAILABLE,
    CREDENTIAL_REJECTED
}
