package io.github.melswg.worldmind.core.administration;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** Storage-only asynchronous read boundary for operator inspection. */
public interface MemoryInspectionRepository {
    CompletionStage<MemoryInspectionPage> inspect(MemoryInspectionQuery query);

    CompletionStage<Optional<MemoryAuditRecord>> detail(MemoryInspectionScope scope, MemoryRecordType recordType, String stableIdentity);
}
