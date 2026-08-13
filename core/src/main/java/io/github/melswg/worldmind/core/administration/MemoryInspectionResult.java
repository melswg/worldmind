package io.github.melswg.worldmind.core.administration;

import java.util.Objects;
import java.util.Optional;

/** Operational result that keeps unavailable storage distinct from an empty page. */
public record MemoryInspectionResult(
    AdministrationResultCode code,
    Optional<MemoryInspectionPage> page,
    Optional<MemoryAuditRecord> record
) {
    public MemoryInspectionResult {
        Objects.requireNonNull(code, "code");
        page = Optional.ofNullable(Objects.requireNonNull(page, "page").orElse(null));
        record = Optional.ofNullable(Objects.requireNonNull(record, "record").orElse(null));
    }

    public static MemoryInspectionResult page(MemoryInspectionPage value) {
        return new MemoryInspectionResult(AdministrationResultCode.SUCCESS, Optional.of(value), Optional.empty());
    }

    public static MemoryInspectionResult detail(MemoryAuditRecord value) {
        return new MemoryInspectionResult(AdministrationResultCode.SUCCESS, Optional.empty(), Optional.of(value));
    }

    public static MemoryInspectionResult of(AdministrationResultCode code) {
        return new MemoryInspectionResult(code, Optional.empty(), Optional.empty());
    }
}
