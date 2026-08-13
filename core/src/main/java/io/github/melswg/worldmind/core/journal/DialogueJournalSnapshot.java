package io.github.melswg.worldmind.core.journal;

import io.github.melswg.worldmind.core.conversation.WorldIdentity;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Stable read contract for journal inspection and high-level acceptance tests. */
public record DialogueJournalSnapshot(
    WorldIdentity worldIdentity,
    List<JournaledObservation> observations,
    List<JournaledBatch> batches,
    Map<UUID, JournalBatchOutcome> outcomes
) {
    public DialogueJournalSnapshot {
        Objects.requireNonNull(worldIdentity, "worldIdentity");
        observations = List.copyOf(Objects.requireNonNull(observations, "observations"));
        batches = List.copyOf(Objects.requireNonNull(batches, "batches"));
        outcomes = Map.copyOf(Objects.requireNonNull(outcomes, "outcomes"));
    }
}
