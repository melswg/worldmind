package io.github.melswg.worldmind.core.administration;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** At most five records plus a position for the next deterministic keyset page. */
public record MemoryInspectionPage(List<MemoryAuditRecord> records, Optional<MemoryInspectionCursor> next) {
    public MemoryInspectionPage {
        records = List.copyOf(Objects.requireNonNull(records, "records"));
        if (records.size() > MemoryInspectionQuery.PAGE_SIZE) throw new IllegalArgumentException("Page exceeds fixed bound.");
        next = Optional.ofNullable(Objects.requireNonNull(next, "next").orElse(null));
    }
}
