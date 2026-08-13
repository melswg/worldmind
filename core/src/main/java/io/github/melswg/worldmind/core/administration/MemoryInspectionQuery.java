package io.github.melswg.worldmind.core.administration;

import java.util.Objects;
import java.util.Optional;

/** Fixed-size page query. Command adapters must not increase the page size. */
public record MemoryInspectionQuery(
    MemoryInspectionScope scope,
    MemoryRecordType recordType,
    Optional<MemoryInspectionCursor> after
) {
    public static final int PAGE_SIZE = 5;

    public MemoryInspectionQuery {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(recordType, "recordType");
        after = Optional.ofNullable(Objects.requireNonNull(after, "after").orElse(null));
        after.ifPresent(cursor -> {
            if (cursor.recordType() != recordType || !cursor.scopeFingerprint().equals(scope.fingerprint())) {
                throw new IllegalArgumentException("Invalid cursor.");
            }
        });
    }
}
