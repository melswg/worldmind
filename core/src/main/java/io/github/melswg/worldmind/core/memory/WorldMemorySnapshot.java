package io.github.melswg.worldmind.core.memory;

import io.github.melswg.worldmind.core.conversation.WorldIdentity;
import java.util.List;
import java.util.Objects;

/** Stable public audit view of all facts and relationships in one world database. */
public record WorldMemorySnapshot(WorldIdentity worldIdentity, List<MemoryRecord> records) {
    public WorldMemorySnapshot {
        Objects.requireNonNull(worldIdentity, "worldIdentity");
        records = List.copyOf(Objects.requireNonNull(records, "records"));
    }
}
