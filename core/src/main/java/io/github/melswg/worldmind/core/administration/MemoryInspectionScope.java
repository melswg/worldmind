package io.github.melswg.worldmind.core.administration;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Explicit authorization scope; player history is always keyed solely by UUID. */
public record MemoryInspectionScope(Kind kind, Optional<UUID> playerId) {
    public enum Kind { WORLD, PLAYER }

    public MemoryInspectionScope {
        Objects.requireNonNull(kind, "kind");
        playerId = Optional.ofNullable(Objects.requireNonNull(playerId, "playerId").orElse(null));
        if ((kind == Kind.PLAYER) != playerId.isPresent()) {
            throw new IllegalArgumentException("Player scope requires exactly one player UUID.");
        }
    }

    public static MemoryInspectionScope world() { return new MemoryInspectionScope(Kind.WORLD, Optional.empty()); }
    public static MemoryInspectionScope player(UUID playerId) { return new MemoryInspectionScope(Kind.PLAYER, Optional.of(playerId)); }

    public String fingerprint() {
        return kind == Kind.WORLD ? "world" : "player:" + playerId.orElseThrow();
    }
}
