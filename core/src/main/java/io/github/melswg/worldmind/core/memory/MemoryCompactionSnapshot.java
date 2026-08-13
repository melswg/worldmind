package io.github.melswg.worldmind.core.memory;

import io.github.melswg.worldmind.core.conversation.WorldIdentity;
import java.util.List;
import java.util.Objects;

/** Stable audit snapshot of all durable derived-memory records for one world. */
public record MemoryCompactionSnapshot(
    WorldIdentity worldIdentity,
    List<MemoryEvent> events,
    List<MemoryChunkSummary> summaries,
    List<CurrentSituationVersion> currentSituations
) {
    public MemoryCompactionSnapshot {
        Objects.requireNonNull(worldIdentity, "worldIdentity");
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        summaries = List.copyOf(Objects.requireNonNull(summaries, "summaries"));
        currentSituations = List.copyOf(Objects.requireNonNull(currentSituations, "currentSituations"));
    }
}
