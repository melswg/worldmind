package io.github.melswg.worldmind.core.administration;

import java.util.concurrent.CompletionStage;

/** Storage-owned prepare/execute boundary for transactional memory deletion. */
public interface MemoryDeletionRepository {
    CompletionStage<MemoryDeletionPreview> prepareDeletion(MemoryDeletionRequest request);

    CompletionStage<MemoryDeletionResult> executeDeletion(MemoryDeletionRequest request, String expectedFingerprint);
}
