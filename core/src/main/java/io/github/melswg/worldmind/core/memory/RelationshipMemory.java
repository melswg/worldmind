package io.github.melswg.worldmind.core.memory;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Auditable relationship state concerning one stable player UUID. */
public record RelationshipMemory(
    MemoryRecordId id,
    MemoryRecordState state,
    MemoryScope scope,
    MemoryVisibility visibility,
    MemoryProvenance provenance,
    Instant sourceTimestamp,
    Instant recordedAt,
    MemoryConfidence confidence,
    MemoryImportance importance,
    Optional<MemoryConfirmation> confirmation,
    UUID subjectPlayerId,
    String relationshipState
) implements MemoryRecord {
    public RelationshipMemory {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(visibility, "visibility");
        Objects.requireNonNull(provenance, "provenance");
        Objects.requireNonNull(sourceTimestamp, "sourceTimestamp");
        Objects.requireNonNull(recordedAt, "recordedAt");
        Objects.requireNonNull(confidence, "confidence");
        Objects.requireNonNull(importance, "importance");
        confirmation = Objects.requireNonNull(confirmation, "confirmation");
        MemoryFact.requireStateConsistency(state, confirmation);
        Objects.requireNonNull(subjectPlayerId, "subjectPlayerId");
        Objects.requireNonNull(relationshipState, "relationshipState");
        if (relationshipState.isBlank()) throw new IllegalArgumentException("relationshipState must not be blank.");
    }
}
