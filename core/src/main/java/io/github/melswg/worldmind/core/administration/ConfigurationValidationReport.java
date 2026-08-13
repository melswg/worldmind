package io.github.melswg.worldmind.core.administration;

import io.github.melswg.worldmind.core.configuration.ConfigurationDiagnostic;
import io.github.melswg.worldmind.core.configuration.DisabledWorldmindIntegration;
import io.github.melswg.worldmind.core.configuration.WorldmindIntegrationState;
import java.util.List;
import java.util.Objects;

/** Read-only result of validating current configuration files. */
public record ConfigurationValidationReport(
    AdministrationResultCode code,
    boolean valid,
    List<ConfigurationDiagnostic> diagnostics,
    WorldmindIntegrationState integrationState
) {
    public ConfigurationValidationReport {
        Objects.requireNonNull(code, "code");
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
        Objects.requireNonNull(integrationState, "integrationState");
        if (valid && !diagnostics.isEmpty()) {
            throw new IllegalArgumentException("Valid reports cannot contain diagnostics.");
        }
    }

    public static ConfigurationValidationReport fromIntegrationState(WorldmindIntegrationState state) {
        Objects.requireNonNull(state, "state");
        if (state instanceof DisabledWorldmindIntegration disabled) {
            return new ConfigurationValidationReport(
                AdministrationResultCode.VALIDATION_FAILED,
                false,
                disabled.diagnostics().stream().map(ConfigurationValidationReport::safeDiagnostic).toList(),
                state
            );
        }
        return new ConfigurationValidationReport(AdministrationResultCode.SUCCESS, true, List.of(), state);
    }

    private static ConfigurationDiagnostic safeDiagnostic(ConfigurationDiagnostic diagnostic) {
        String field = supportedField(diagnostic.field()) ? diagnostic.field() : "configuration";
        String reason = diagnostic.reason();
        String category;
        if (reason.contains("is required")) category = "is required.";
        else if (reason.contains("not supported")) category = "is not supported by the strict v1 schema.";
        else if (reason.contains("cannot be read")) category = "cannot be read.";
        else if (reason.contains("secret") || reason.contains("Credential")) category = "credential material is unavailable.";
        else category = "is invalid.";
        return new ConfigurationDiagnostic(field, category);
    }

    private static boolean supportedField(String field) {
        return field.matches("global\\.(schemaVersion|enabled|activeProfile|chatBatching|requestQueue|provider)")
            || field.matches("global\\.chatBatching\\.(maxMessages|maxWaitMillis|maxEstimatedInputCharacters)")
            || field.matches("global\\.requestQueue\\.(capacity|maxConcurrency)")
            || field.matches("global\\.provider\\.(id|endpoint|model|secretReference|generation|timeouts|retry|circuitBreaker)")
            || field.matches("global\\.provider\\.generation\\.(temperature|topP|maxOutputTokens)")
            || field.matches("global\\.provider\\.timeouts\\.(connectMillis|responseCompletionMillis)")
            || field.matches("global\\.provider\\.retry\\.(maximumAttempts|initialBackoffMillis|maximumBackoffMillis|jitterRatio)")
            || field.matches("global\\.provider\\.circuitBreaker\\.(failureThreshold|cooldownMillis)")
            || field.matches("profile(\\.(schemaVersion|characterName|personaFile|administratorRulesFile|loreFiles|responseStyle|responseLengthLimit|chatNameColor))?");
    }
}
