package io.github.melswg.worldmind.gamecontext.internal;

import java.util.Objects;
import java.util.Optional;

/** Content-free internal accounting for the public-extension boundary. */
public record GameContextRuntimeSnapshot(
    int registered,
    int active,
    int quarantined,
    int inFlight,
    Optional<GameContextDiagnostic> latestDiagnostic
) {
    public GameContextRuntimeSnapshot {
        if (registered < 0 || active < 0 || quarantined < 0 || inFlight < 0 || active + quarantined > registered) {
            throw new IllegalArgumentException("Invalid game-context runtime accounting.");
        }
        latestDiagnostic = Optional.ofNullable(Objects.requireNonNull(latestDiagnostic, "latestDiagnostic").orElse(null));
    }
}
