package io.github.melswg.worldmind.core.memory;

import java.util.Objects;
import java.util.UUID;

/** Links a derived memory record to a persisted sealed-batch range. */
public record MemoryProvenance(UUID sourceBatchId, JournalSequenceRange sourceRange) {
    public MemoryProvenance {
        Objects.requireNonNull(sourceBatchId, "sourceBatchId");
        Objects.requireNonNull(sourceRange, "sourceRange");
    }
}
