package io.github.melswg.worldmind.core.configuration;

import java.util.Objects;

/** A field-specific, operator-readable reason why configuration cannot be used. */
public record ConfigurationDiagnostic(String field, String reason) {
    public ConfigurationDiagnostic {
        field = requireText(field, "field");
        reason = requireText(reason, "reason");
    }

    public String message() {
        return field + ": " + reason;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }
}
