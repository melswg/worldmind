package io.github.melswg.worldmind.core.memory;

import java.util.Objects;

/** Untrusted generated payload; storage assigns all durable identities and versions. */
public record DerivedMemoryCandidate(
    MemoryScope scope,
    MemoryVisibility visibility,
    MemoryConfidence confidence,
    MemoryImportance importance,
    String content
) {
    public DerivedMemoryCandidate {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(visibility, "visibility");
        Objects.requireNonNull(confidence, "confidence");
        Objects.requireNonNull(importance, "importance");
        Objects.requireNonNull(content, "content");
        if (content.isBlank()) throw new IllegalArgumentException("content must not be blank.");
    }
}
