package io.github.melswg.worldmind.core.memory;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Exact raw range and ordered sealed batches condensed by a derived memory record. */
public record DerivedMemoryProvenance(JournalSequenceRange sourceRange, List<UUID> sourceBatchIds) {
    public DerivedMemoryProvenance {
        Objects.requireNonNull(sourceRange, "sourceRange");
        sourceBatchIds = List.copyOf(Objects.requireNonNull(sourceBatchIds, "sourceBatchIds"));
        if (sourceBatchIds.isEmpty() || sourceBatchIds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("sourceBatchIds must contain at least one batch.");
        }
    }
}
