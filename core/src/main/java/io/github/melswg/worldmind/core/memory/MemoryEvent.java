package io.github.melswg.worldmind.core.memory;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable, auditable event derived from persisted public dialogue. */
public record MemoryEvent(
    UUID id,
    MemoryScope scope,
    MemoryVisibility visibility,
    DerivedMemoryProvenance provenance,
    Instant sourceTimestamp,
    Instant recordedAt,
    MemoryConfidence confidence,
    MemoryImportance importance,
    String content
) {
    public MemoryEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(visibility, "visibility");
        Objects.requireNonNull(provenance, "provenance");
        Objects.requireNonNull(sourceTimestamp, "sourceTimestamp");
        Objects.requireNonNull(recordedAt, "recordedAt");
        Objects.requireNonNull(confidence, "confidence");
        Objects.requireNonNull(importance, "importance");
        requireBoundedContent(content, 600, "event");
    }

    public static void requireBoundedContent(String content, int maximumCodePoints, String kind) {
        Objects.requireNonNull(content, "content");
        if (content.isBlank() || content.codePointCount(0, content.length()) > maximumCodePoints) {
            throw new IllegalArgumentException(kind + " content must be non-blank and at most " + maximumCodePoints + " code points.");
        }
    }
}
