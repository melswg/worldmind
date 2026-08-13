package io.github.melswg.worldmind.core.conversation;

import java.time.Instant;
import java.util.Optional;

/** Contains no endpoint, credential, world, player, or chat data. */
public record ProviderCircuitSnapshot(
    ProviderCircuitState state,
    int consecutiveQualifyingFailures,
    Optional<Instant> cooldownUntil,
    boolean probeInFlight
) { }
