package io.github.melswg.worldmind.releaseverification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class ReleaseAuditMainTest {
    @Test
    void detectsEachSecretRuleWithoutKeepingTheSyntheticValueInSource() {
        assertTrue(ReleaseAuditMain.matchingSecretRuleIds("token=" + "s" + "k-abcdefghijklmnop").contains("provider-token"));
        assertTrue(ReleaseAuditMain.matchingSecretRuleIds("Authorization: Bea" + "rer abcdefghijklmnop").contains("bearer-value"));
        assertTrue(ReleaseAuditMain.matchingSecretRuleIds("-----BE" + "GIN PRIVATE KEY-----").contains("private-key"));
        assertTrue(ReleaseAuditMain.matchingSecretRuleIds("api" + "_key=\"abcdefghijklmnop\"").contains("credential-assignment"));
        assertTrue(ReleaseAuditMain.matchingSecretRuleIds("https://user" + ":password@example.invalid").contains("credential-uri"));
    }

    @Test
    void permitsSyntheticReferencesAndPlaceholderEndpoints() {
        assertEquals(Set.of(), ReleaseAuditMain.matchingSecretRuleIds("env:WORLDMIND_API_KEY https://provider.example/v1/chat/completions"));
    }
}
