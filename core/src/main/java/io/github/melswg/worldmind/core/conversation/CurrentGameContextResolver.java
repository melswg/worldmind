package io.github.melswg.worldmind.core.conversation;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Resolves the immutable current-game-context snapshot for a sealed batch.
 * Implementations must not expose their own data as trusted prompt material.
 */
@FunctionalInterface
public interface CurrentGameContextResolver {
    CompletionStage<List<UntrustedContext>> resolve(SealedChatBatch batch);

    static CurrentGameContextResolver vanillaOnly() {
        return batch -> CompletableFuture.completedFuture(
            Objects.requireNonNull(batch, "batch").currentContextSnapshot()
        );
    }
}
