package io.github.melswg.worldmind.core.administration;

import java.util.Objects;
import java.util.Optional;

/** Bounded (128-record) page used only by the portable streaming exporter. */
public record MemoryExportQuery(
    MemoryInspectionScope scope,
    MemoryRecordType recordType,
    Optional<MemoryInspectionCursor> after
) {
    public static final int PAGE_SIZE = 128;

    public MemoryExportQuery {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(recordType, "recordType");
        after = Optional.ofNullable(Objects.requireNonNull(after, "after").orElse(null));
        after.ifPresent(cursor -> {
            if (cursor.recordType() != recordType || !cursor.scopeFingerprint().equals(scope.fingerprint())) {
                throw new IllegalArgumentException("Invalid export cursor.");
            }
        });
    }
}
