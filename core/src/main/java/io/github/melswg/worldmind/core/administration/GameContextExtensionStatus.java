package io.github.melswg.worldmind.core.administration;

import java.util.Objects;
import java.util.Optional;

/** Redaction-safe public-extension accounting; no context, exception or filesystem data is exposed. */
public record GameContextExtensionStatus(
    int registered,
    int active,
    int quarantined,
    int inFlight,
    Optional<String> latestProvider,
    Optional<String> latestDiagnosticCode
) {
    public GameContextExtensionStatus {
        if (registered < 0 || active < 0 || quarantined < 0 || inFlight < 0 || active + quarantined > registered) {
            throw new IllegalArgumentException("Invalid game-context extension accounting.");
        }
        latestProvider = optional(latestProvider, "latestProvider");
        latestDiagnosticCode = optional(latestDiagnosticCode, "latestDiagnosticCode");
    }

    private static Optional<String> optional(Optional<String> value, String name) {
        return Optional.ofNullable(Objects.requireNonNull(value, name).orElse(null));
    }
}
