package io.github.melswg.worldmind.core.administration;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Safe prepare response. The fingerprint is internal and never rendered by Fabric. */
public record MemoryDeletionPreview(
    AdministrationResultCode code,
    Optional<ConfirmationToken> token,
    int affectedRecords,
    Optional<Instant> expiresAt,
    Optional<String> targetFingerprint
) {
    public MemoryDeletionPreview {
        Objects.requireNonNull(code, "code");
        token = optional(token, "token");
        if (affectedRecords < 0) throw new IllegalArgumentException("affectedRecords must not be negative.");
        expiresAt = optional(expiresAt, "expiresAt");
        targetFingerprint = optional(targetFingerprint, "targetFingerprint");
    }

    public static MemoryDeletionPreview of(AdministrationResultCode code) {
        return new MemoryDeletionPreview(code, Optional.empty(), 0, Optional.empty(), Optional.empty());
    }

    private static <T> Optional<T> optional(Optional<T> value, String name) {
        return Optional.ofNullable(Objects.requireNonNull(value, name).orElse(null));
    }
}
