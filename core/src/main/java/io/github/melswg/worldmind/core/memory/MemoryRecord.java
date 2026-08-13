package io.github.melswg.worldmind.core.memory;

import java.time.Instant;
import java.util.Optional;

/** Common auditable metadata for every durable fact or relationship record. */
public sealed interface MemoryRecord permits MemoryFact, RelationshipMemory {
    MemoryRecordId id();
    MemoryRecordState state();
    MemoryScope scope();
    MemoryVisibility visibility();
    MemoryProvenance provenance();
    Instant sourceTimestamp();
    Instant recordedAt();
    MemoryConfidence confidence();
    MemoryImportance importance();
    Optional<MemoryConfirmation> confirmation();
}
