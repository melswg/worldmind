package io.github.melswg.worldmind.core.memory;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Fully attributed untrusted memory selected before prompt budgeting. */
public record RetrievedMemoryEntry(
    RetrievedMemoryRecordType type,
    UUID identity,
    DerivedMemoryProvenance provenance,
    Instant sourceTimestamp,
    Instant recordedAt,
    MemoryConfidence confidence,
    MemoryImportance importance,
    MemoryScope scope,
    MemoryVisibility visibility,
    String content
) {
    public RetrievedMemoryEntry {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(provenance, "provenance");
        Objects.requireNonNull(sourceTimestamp, "sourceTimestamp");
        Objects.requireNonNull(recordedAt, "recordedAt");
        Objects.requireNonNull(confidence, "confidence");
        Objects.requireNonNull(importance, "importance");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(visibility, "visibility");
        MemoryEvent.requireBoundedContent(content, 600, "retrieved memory");
    }
}
