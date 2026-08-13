package io.github.melswg.worldmind.core.memory;

import java.util.Objects;
import java.util.UUID;

/** New relationship-state candidate with an explicit stable player UUID. */
public record ProposedRelationshipCandidate(
    MemoryScope scope,
    MemoryVisibility visibility,
    JournalSequenceRange sourceRange,
    MemoryConfidence confidence,
    MemoryImportance importance,
    UUID subjectPlayerId,
    String relationshipState
) implements ProposedMemoryCandidate {
    public ProposedRelationshipCandidate {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(visibility, "visibility");
        Objects.requireNonNull(sourceRange, "sourceRange");
        Objects.requireNonNull(confidence, "confidence");
        Objects.requireNonNull(importance, "importance");
        Objects.requireNonNull(subjectPlayerId, "subjectPlayerId");
        Objects.requireNonNull(relationshipState, "relationshipState");
        if (relationshipState.isBlank()) throw new IllegalArgumentException("relationshipState must not be blank.");
    }
}
