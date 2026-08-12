package io.github.melswg.worldmind.core.configuration;

import java.util.List;
import java.util.Objects;

/** A safe startup state in which Minecraft continues without an LLM integration. */
public record DisabledWorldmindIntegration(
    IntegrationDisableReason reason,
    List<ConfigurationDiagnostic> diagnostics
) implements WorldmindIntegrationState {
    public DisabledWorldmindIntegration {
        Objects.requireNonNull(reason, "reason");
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
        if (diagnostics.isEmpty()) {
            throw new IllegalArgumentException("diagnostics must not be empty for a disabled integration.");
        }
    }
}
