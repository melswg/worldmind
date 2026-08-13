package io.github.melswg.worldmind.core.administration;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Full-fidelity portable record. Only the exporter may consume unbounded text/membership. */
public record MemoryExportRecord(
    String stableIdentity,
    MemoryRecordType recordType,
    long firstSequence,
    long lastSequence,
    MemoryInspectionScope scope,
    String visibility,
    String sourceType,
    Instant sourceTimestamp,
    Instant recordedAt,
    Optional<Double> confidence,
    Optional<Double> importance,
    Optional<String> state,
    Optional<UUID> relationshipSubjectPlayerId,
    Optional<Integer> version,
    Optional<Boolean> latest,
    Optional<String> supersededBy,
    MemoryAuditProvenance provenance,
    Optional<UUID> actorPlayerId,
    String content,
    List<Long> membershipSequences,
    Optional<String> confirmationAuthority,
    Optional<Instant> confirmedAt
) {
    public MemoryExportRecord {
        stableIdentity = Objects.requireNonNull(stableIdentity, "stableIdentity");
        Objects.requireNonNull(recordType, "recordType");
        if (firstSequence < 0 || lastSequence < firstSequence) throw new IllegalArgumentException("Invalid sequence range.");
        Objects.requireNonNull(scope, "scope");
        visibility = Objects.requireNonNull(visibility, "visibility");
        sourceType = Objects.requireNonNull(sourceType, "sourceType");
        Objects.requireNonNull(sourceTimestamp, "sourceTimestamp");
        Objects.requireNonNull(recordedAt, "recordedAt");
        confidence = optional(confidence, "confidence");
        importance = optional(importance, "importance");
        state = optional(state, "state");
        relationshipSubjectPlayerId = optional(relationshipSubjectPlayerId, "relationshipSubjectPlayerId");
        version = optional(version, "version");
        latest = optional(latest, "latest");
        supersededBy = optional(supersededBy, "supersededBy");
        Objects.requireNonNull(provenance, "provenance");
        actorPlayerId = optional(actorPlayerId, "actorPlayerId");
        content = Objects.requireNonNull(content, "content");
        membershipSequences = List.copyOf(Objects.requireNonNull(membershipSequences, "membershipSequences"));
        confirmationAuthority = optional(confirmationAuthority, "confirmationAuthority");
        confirmedAt = optional(confirmedAt, "confirmedAt");
    }

    private static <T> Optional<T> optional(Optional<T> value, String name) {
        return Optional.ofNullable(Objects.requireNonNull(value, name).orElse(null));
    }
}
