package io.github.melswg.worldmind.fabric.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melswg.worldmind.core.configuration.ExternalSecretReference;
import io.github.melswg.worldmind.core.configuration.SecretAvailability;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EnvironmentProviderCredentialResolverTest {
    @Test
    void resolvesOnlyConfiguredEnvironmentNamesAndReportsMissingMaterial() {
        String generatedMaterial = UUID.randomUUID().toString();
        EnvironmentProviderCredentialResolver resolver = new EnvironmentProviderCredentialResolver(
            Map.of("WORLDMIND_TEST_KEY", generatedMaterial)::get
        );

        ExternalSecretReference available = new ExternalSecretReference("env:WORLDMIND_TEST_KEY");
        assertEquals(SecretAvailability.AVAILABLE, resolver.check(available));
        assertTrue(resolver.resolveForOutgoingRequest(available).isPresent());

        assertEquals(
            SecretAvailability.MISSING,
            resolver.check(new ExternalSecretReference("env:NOT_PRESENT"))
        );
        assertFalse(resolver.resolveForOutgoingRequest(new ExternalSecretReference("env:NOT_PRESENT")).isPresent());
        assertEquals(
            SecretAvailability.UNREADABLE,
            resolver.check(new ExternalSecretReference("not-an-environment-reference"))
        );
    }
}
