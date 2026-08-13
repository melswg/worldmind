package io.github.melswg.worldmind.core.administration;

import java.util.Objects;

/** Terminal destructive-operation result, intentionally free of raw data and paths. */
public record MemoryDeletionResult(AdministrationResultCode code, MemoryDeletionKind kind, int affectedRecords, boolean logicalOnly) {
    public MemoryDeletionResult {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(kind, "kind");
        if (affectedRecords < 0) throw new IllegalArgumentException("affectedRecords must not be negative.");
    }

    public static MemoryDeletionResult of(AdministrationResultCode code, MemoryDeletionKind kind) {
        return new MemoryDeletionResult(code, kind, 0, true);
    }
}
