package io.github.melswg.worldmind.core.memory;

import java.util.Objects;

/** New fact candidate; record identity, timestamps, and state are assigned by storage. */
public record ProposedFactCandidate(
    MemoryScope scope,
    MemoryVisibility visibility,
    JournalSequenceRange sourceRange,
    MemoryConfidence confidence,
    MemoryImportance importance,
    String content
) implements ProposedMemoryCandidate {
    public ProposedFactCandidate {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(visibility, "visibility");
        Objects.requireNonNull(sourceRange, "sourceRange");
        Objects.requireNonNull(confidence, "confidence");
        Objects.requireNonNull(importance, "importance");
        Objects.requireNonNull(content, "content");
        if (content.isBlank()) throw new IllegalArgumentException("content must not be blank.");
    }
}
