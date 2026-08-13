package io.github.melswg.worldmind.core.memory;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Append-only version of one summary series; raw dialogue remains the source of truth. */
public record MemoryChunkSummary(
    UUID seriesId,
    UUID versionId,
    int version,
    MemoryScope scope,
    MemoryVisibility visibility,
    DerivedMemoryProvenance provenance,
    Instant sourceTimestamp,
    Instant recordedAt,
    MemoryConfidence confidence,
    MemoryImportance importance,
    String content
) {
    public MemoryChunkSummary {
        Objects.requireNonNull(seriesId, "seriesId");
        Objects.requireNonNull(versionId, "versionId");
        if (version <= 0) throw new IllegalArgumentException("version must be positive.");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(visibility, "visibility");
        Objects.requireNonNull(provenance, "provenance");
        Objects.requireNonNull(sourceTimestamp, "sourceTimestamp");
        Objects.requireNonNull(recordedAt, "recordedAt");
        Objects.requireNonNull(confidence, "confidence");
        Objects.requireNonNull(importance, "importance");
        MemoryEvent.requireBoundedContent(content, 1_200, "summary");
    }
}
