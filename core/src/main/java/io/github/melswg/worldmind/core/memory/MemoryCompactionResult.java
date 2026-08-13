package io.github.melswg.worldmind.core.memory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Typed compaction output that remains data until persisted with validated provenance. */
public record MemoryCompactionResult(
    List<DerivedMemoryCandidate> events,
    Optional<DerivedMemoryCandidate> summary,
    Optional<DerivedMemoryCandidate> currentSituation
) {
    public MemoryCompactionResult {
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        if (events.size() > 8 || events.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("A compaction result may contain at most eight events.");
        }
        summary = Objects.requireNonNull(summary, "summary");
        currentSituation = Objects.requireNonNull(currentSituation, "currentSituation");
    }
}
