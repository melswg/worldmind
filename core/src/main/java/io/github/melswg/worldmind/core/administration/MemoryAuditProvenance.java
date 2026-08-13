package io.github.melswg.worldmind.core.administration;

import java.util.List;
import java.util.Objects;

/** Bounded visible provenance. Full ordered membership remains available to the export surface. */
public record MemoryAuditProvenance(long firstSequence, long lastSequence, List<String> sourceBatchIds) {
    public MemoryAuditProvenance {
        if (firstSequence < 0 || lastSequence < firstSequence) throw new IllegalArgumentException("Invalid source range.");
        sourceBatchIds = List.copyOf(Objects.requireNonNull(sourceBatchIds, "sourceBatchIds"));
    }
}
