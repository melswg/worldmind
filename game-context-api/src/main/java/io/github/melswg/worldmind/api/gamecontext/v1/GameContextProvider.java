package io.github.melswg.worldmind.api.gamecontext.v1;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * External source of optional context for a sealed public-chat batch.
 * Worldmind invokes every method on its own worker, never on the Minecraft
 * server thread. Returned data is always untrusted prompt data.
 */
public interface GameContextProvider {
    GameContextSource source();

    default CompletionStage<Void> onServerStart(GameContextServerContext server) {
        return CompletableFuture.completedFuture(null);
    }

    default CompletionStage<Void> onWorldLoad(GameContextWorldContext world) {
        return CompletableFuture.completedFuture(null);
    }

    default CompletionStage<Void> onReload(GameContextReloadContext reload) {
        return CompletableFuture.completedFuture(null);
    }

    default CompletionStage<Void> onWorldUnload(GameContextWorldContext world) {
        return CompletableFuture.completedFuture(null);
    }

    default CompletionStage<Void> onServerShutdown(GameContextServerContext server) {
        return CompletableFuture.completedFuture(null);
    }

    default CompletionStage<Void> onCleanup() {
        return CompletableFuture.completedFuture(null);
    }

    CompletionStage<GameContextResult> provide(GameContextRequest request);
}
