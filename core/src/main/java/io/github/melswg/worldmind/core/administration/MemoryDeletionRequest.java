package io.github.melswg.worldmind.core.administration;

import java.util.Objects;
import java.util.Optional;

/** Content-free canonical target for an authorized destructive operation. */
public record MemoryDeletionRequest(
    MemoryDeletionKind kind,
    MemoryInspectionScope scope,
    Optional<MemoryRecordType> recordType,
    Optional<String> stableIdentity
) {
    public MemoryDeletionRequest {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(scope, "scope");
        recordType = optional(recordType, "recordType");
        stableIdentity = optional(stableIdentity, "stableIdentity");
        if (kind == MemoryDeletionKind.DELETE_RECORD) {
            if (recordType.isEmpty() || stableIdentity.isEmpty()) throw new IllegalArgumentException("Record deletion needs a stable record identity.");
            String expected = recordType.orElseThrow().commandValue() + ":";
            if (!stableIdentity.orElseThrow().startsWith(expected) || stableIdentity.orElseThrow().length() > 160) {
                throw new IllegalArgumentException("Record identity does not match its record type.");
            }
        } else if (kind == MemoryDeletionKind.DELETE_PLAYER) {
            if (scope.kind() != MemoryInspectionScope.Kind.PLAYER || recordType.isPresent() || stableIdentity.isPresent()) {
                throw new IllegalArgumentException("Player deletion needs exactly one canonical player UUID.");
            }
        } else if (scope.kind() != MemoryInspectionScope.Kind.WORLD || recordType.isPresent() || stableIdentity.isPresent()) {
            throw new IllegalArgumentException("World reset has no player or record target.");
        }
    }

    public static MemoryDeletionRequest record(MemoryInspectionScope scope, MemoryRecordType type, String stableIdentity) {
        return new MemoryDeletionRequest(MemoryDeletionKind.DELETE_RECORD, scope, Optional.of(type), Optional.of(stableIdentity));
    }

    public static MemoryDeletionRequest player(java.util.UUID playerId) {
        return new MemoryDeletionRequest(MemoryDeletionKind.DELETE_PLAYER, MemoryInspectionScope.player(playerId), Optional.empty(), Optional.empty());
    }

    public static MemoryDeletionRequest worldReset() {
        return new MemoryDeletionRequest(MemoryDeletionKind.RESET_WORLD, MemoryInspectionScope.world(), Optional.empty(), Optional.empty());
    }

    private static <T> Optional<T> optional(Optional<T> value, String name) {
        return Optional.ofNullable(Objects.requireNonNull(value, name).orElse(null));
    }
}
