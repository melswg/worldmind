package io.github.melswg.worldmind.core.memory;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Auditable assertion retained about the world or one player. */
public record MemoryFact(
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
    String content
) implements MemoryRecord {
    public MemoryFact {
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
        requireStateConsistency(state, confirmation);
        Objects.requireNonNull(content, "content");
        if (content.isBlank()) throw new IllegalArgumentException("content must not be blank.");
    }

    static void requireStateConsistency(MemoryRecordState state, Optional<MemoryConfirmation> confirmation) {
        if (state == MemoryRecordState.CONFIRMED && confirmation.isEmpty()) {
            throw new IllegalArgumentException("confirmed records require a confirmation.");
        }
        if (state == MemoryRecordState.PROPOSED && confirmation.isPresent()) {
            throw new IllegalArgumentException("proposed records cannot have a confirmation.");
        }
    }
}
