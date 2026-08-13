package io.github.melswg.worldmind.core.administration;

import java.util.Objects;
import java.util.Optional;

/** Safe operator result: relative artifact name only, never an absolute server path. */
public record MemoryExportResult(AdministrationResultCode code, Optional<String> relativeArtifact) {
    public MemoryExportResult {
        Objects.requireNonNull(code, "code");
        relativeArtifact = Optional.ofNullable(Objects.requireNonNull(relativeArtifact, "relativeArtifact").orElse(null));
    }

    public static MemoryExportResult of(AdministrationResultCode code) {
        return new MemoryExportResult(code, Optional.empty());
    }

    public static MemoryExportResult completed(String relativeArtifact) {
        return new MemoryExportResult(AdministrationResultCode.SUCCESS, Optional.of(relativeArtifact));
    }
}
