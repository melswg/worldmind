package io.github.melswg.worldmind.fabric;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melswg.worldmind.core.administration.CompactionStatus;
import io.github.melswg.worldmind.core.administration.GameContextExtensionStatus;
import io.github.melswg.worldmind.core.administration.ProviderAvailability;
import io.github.melswg.worldmind.core.administration.RuntimeLifecycleState;
import io.github.melswg.worldmind.core.administration.RuntimeReloadState;
import io.github.melswg.worldmind.core.administration.RuntimeStatusSnapshot;
import io.github.melswg.worldmind.core.administration.StorageHealth;
import io.github.melswg.worldmind.core.administration.WorkStatus;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WorldmindCommandTextTest {
    @Test
    void rendersOnlyCanonicalExtensionIdentityAndDiagnosticCode() {
        String rendered = WorldmindCommandText.status(new RuntimeStatusSnapshot(
            RuntimeLifecycleState.RUNNING,
            RuntimeReloadState.IDLE,
            true,
            Optional.empty(),
            Optional.of("default"),
            Optional.of("provider"),
            ProviderAvailability.READY,
            Optional.empty(),
            new WorkStatus(0, 0, false, 0, 0),
            Optional.empty(),
            StorageHealth.READY,
            new CompactionStatus(0, 0, "NONE"),
            Optional.of(new GameContextExtensionStatus(
                2, 1, 1, 0, Optional.of("example:season"), Optional.of("TIMEOUT")
            ))
        ));

        assertTrue(rendered.contains("extensions=1/2 quarantined=1 inFlight=0 last=example:season:TIMEOUT"));
        assertFalse(rendered.contains("context="));
        assertFalse(rendered.contains("/Users/"));
    }
}
