package io.github.melswg.worldmind.core.memory;

import io.github.melswg.worldmind.core.conversation.WorldIdentity;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Coordinates one best-effort compaction attempt without blocking the caller. */
public final class MemoryCompactionService {
    private final MemoryCompactionRepository repository;
    private final MemoryCompactionGenerator generator;

    public MemoryCompactionService(MemoryCompactionRepository repository, MemoryCompactionGenerator generator) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.generator = Objects.requireNonNull(generator, "generator");
    }

    public CompletionStage<Void> compactNext(WorldIdentity worldIdentity) {
        Objects.requireNonNull(worldIdentity, "worldIdentity");
        return repository.nextCompaction(worldIdentity).thenCompose(input -> input
            .<CompletionStage<Void>>map(value -> generator.compact(value)
                .thenCompose(result -> repository.persistCompaction(value, result)).thenApply(ignored -> null))
            .orElseGet(() -> CompletableFuture.completedFuture(null)));
    }
}
