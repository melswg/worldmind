package io.github.melswg.worldmind.core.administration;

import java.util.Objects;

/** Opaque, short-lived confirmation capability; it intentionally carries no target data. */
public record ConfirmationToken(String value) {
    public ConfirmationToken {
        Objects.requireNonNull(value, "value");
        if (!value.matches("[A-Za-z0-9_-]{16,128}")) throw new IllegalArgumentException("Invalid confirmation token.");
    }
}
