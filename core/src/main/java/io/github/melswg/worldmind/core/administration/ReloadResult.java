package io.github.melswg.worldmind.core.administration;

import io.github.melswg.worldmind.core.configuration.ConfigurationDiagnostic;
import java.util.List;
import java.util.Objects;

/** Terminal redaction-safe outcome of a supported configuration reload. */
public record ReloadResult(AdministrationResultCode code, List<ConfigurationDiagnostic> diagnostics) {
    public ReloadResult {
        Objects.requireNonNull(code, "code");
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
    }

    public boolean accepted() {
        return code == AdministrationResultCode.ACCEPTED || code == AdministrationResultCode.SUCCESS
            || code == AdministrationResultCode.NO_CHANGE;
    }

    public static ReloadResult of(AdministrationResultCode code) {
        return new ReloadResult(code, List.of());
    }
}
