package io.github.melswg.worldmind.core.configuration;

/** Stable, redaction-safe category for a configuration diagnostic. */
public enum ConfigurationDiagnosticCode {
    GENERIC,
    UNKNOWN_PROVIDER_PRESET,
    INVALID_PRESET_CONFIGURATION,
    INCOMPATIBLE_MODEL_OR_PARAMETER,
    CREDENTIAL_MISSING,
    CREDENTIAL_UNREADABLE,
    CREDENTIAL_REJECTED
}
