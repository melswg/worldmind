package io.github.melswg.worldmind.core.memory;

import java.util.Objects;
import java.util.UUID;

/** A record belongs to the current world or to one stable player UUID within it. */
public sealed interface MemoryScope permits MemoryScope.World, MemoryScope.Player {
    /** World scope has no player identity. */
    record World() implements MemoryScope {
    }

    /** Player scope is deliberately keyed by UUID rather than a mutable visible name. */
    record Player(UUID playerId) implements MemoryScope {
        public Player {
            Objects.requireNonNull(playerId, "playerId");
        }
    }

    static World world() {
        return new World();
    }

    static Player player(UUID playerId) {
        return new Player(playerId);
    }
}
