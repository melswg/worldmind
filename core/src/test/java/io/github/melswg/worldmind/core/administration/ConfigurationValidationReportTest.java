package io.github.melswg.worldmind.core.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.melswg.worldmind.core.configuration.ConfigurationDiagnostic;
import io.github.melswg.worldmind.core.configuration.DisabledWorldmindIntegration;
import io.github.melswg.worldmind.core.configuration.IntegrationDisableReason;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConfigurationValidationReportTest {
    @Test
    void validationReportDoesNotCopyUnknownFieldNamesOrValuesIntoOperatorDiagnostics() {
        ConfigurationValidationReport report = ConfigurationValidationReport.fromIntegrationState(
            new DisabledWorldmindIntegration(
                IntegrationDisableReason.INVALID_CONFIGURATION,
                List.of(new ConfigurationDiagnostic("global.untrusted-operator-token", "found user-value"))
            )
        );

        assertFalse(report.valid());
        assertEquals(AdministrationResultCode.VALIDATION_FAILED, report.code());
        assertEquals("configuration", report.diagnostics().get(0).field());
        assertEquals("is invalid.", report.diagnostics().get(0).reason());
    }
}
