package io.github.melswg.worldmind.core.memory;

import io.github.melswg.worldmind.core.conversation.SealedChatBatch;
import io.github.melswg.worldmind.core.journal.JournaledBatch;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Asynchronous, world-owned storage boundary for auditable long-term memory. */
public interface WorldMemoryRepository {
    CompletionStage<List<MemoryRecord>> appendProposed(JournaledBatch sourceBatch, List<? extends ProposedMemoryCandidate> candidates);

    CompletionStage<MemoryRecord> confirm(MemoryRecordId recordId, MemoryConfirmationRequest confirmation);

    CompletionStage<List<MemoryRecord>> recallPublic(SealedChatBatch nextBatch);

    CompletionStage<WorldMemorySnapshot> readMemorySnapshot();

    /** Safe default for integrations that have no persistent memory store yet. */
    static WorldMemoryRepository empty() {
        return EmptyWorldMemoryRepository.INSTANCE;
    }

    enum EmptyWorldMemoryRepository implements WorldMemoryRepository {
        INSTANCE;

        @Override
        public CompletionStage<List<MemoryRecord>> appendProposed(
            JournaledBatch sourceBatch,
            List<? extends ProposedMemoryCandidate> candidates
        ) {
            Objects.requireNonNull(sourceBatch, "sourceBatch");
            Objects.requireNonNull(candidates, "candidates");
            return CompletableFuture.failedFuture(new UnsupportedOperationException("World memory is not configured."));
        }

        @Override
        public CompletionStage<MemoryRecord> confirm(MemoryRecordId recordId, MemoryConfirmationRequest confirmation) {
            Objects.requireNonNull(recordId, "recordId");
            Objects.requireNonNull(confirmation, "confirmation");
            return CompletableFuture.failedFuture(new UnsupportedOperationException("World memory is not configured."));
        }

        @Override
        public CompletionStage<List<MemoryRecord>> recallPublic(SealedChatBatch nextBatch) {
            Objects.requireNonNull(nextBatch, "nextBatch");
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public CompletionStage<WorldMemorySnapshot> readMemorySnapshot() {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("World memory is not configured."));
        }
    }
}
