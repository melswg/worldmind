package io.github.melswg.worldmind.core.administration;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** One streaming page, never a whole-world collection. */
public record MemoryExportPage(List<MemoryExportRecord> records, Optional<MemoryInspectionCursor> next) {
    public MemoryExportPage {
        records = List.copyOf(Objects.requireNonNull(records, "records"));
        if (records.size() > MemoryExportQuery.PAGE_SIZE) throw new IllegalArgumentException("Export page exceeds bound.");
        next = Optional.ofNullable(Objects.requireNonNull(next, "next").orElse(null));
    }
}
