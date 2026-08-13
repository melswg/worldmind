package io.github.melswg.worldmind.core.administration;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Immutable, bounded command DTO; it deliberately excludes display names and any configuration/provider material. */
public record MemoryAuditRecord(
    String stableIdentity,
    MemoryRecordType recordType,
    long firstSequence,
    long lastSequence,
    MemoryInspectionScope actualScope,
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
    String text,
    boolean textTruncated,
    List<Long> membershipSequences,
    boolean membershipTruncated
) {
    public MemoryAuditRecord {
        stableIdentity = text(stableIdentity, "stableIdentity", 160);
        Objects.requireNonNull(recordType, "recordType");
        if (firstSequence < 0 || lastSequence < firstSequence) throw new IllegalArgumentException("Invalid sequence range.");
        Objects.requireNonNull(actualScope, "actualScope");
        visibility = text(visibility, "visibility", 32);
        sourceType = text(sourceType, "sourceType", 48);
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
        text = text(text, "text", 1_024);
        membershipSequences = List.copyOf(Objects.requireNonNull(membershipSequences, "membershipSequences"));
        if (membershipSequences.size() > 64) throw new IllegalArgumentException("Membership must be bounded.");
    }

    private static String text(String value, String name, int maximum) {
        Objects.requireNonNull(value, name);
        if (value.codePointCount(0, value.length()) > maximum) throw new IllegalArgumentException(name + " exceeds bound.");
        return value;
    }

    private static <T> Optional<T> optional(Optional<T> value, String name) {
        return Optional.ofNullable(Objects.requireNonNull(value, name).orElse(null));
    }
}
