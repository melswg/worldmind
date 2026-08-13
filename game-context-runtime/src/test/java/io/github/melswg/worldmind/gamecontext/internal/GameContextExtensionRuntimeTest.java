package io.github.melswg.worldmind.gamecontext.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melswg.worldmind.api.gamecontext.v1.GameContextEntry;
import io.github.melswg.worldmind.api.gamecontext.v1.GameContextProvider;
import io.github.melswg.worldmind.api.gamecontext.v1.GameContextRegistration;
import io.github.melswg.worldmind.api.gamecontext.v1.GameContextRequest;
import io.github.melswg.worldmind.api.gamecontext.v1.GameContextResult;
import io.github.melswg.worldmind.api.gamecontext.v1.GameContextServerContext;
import io.github.melswg.worldmind.api.gamecontext.v1.GameContextSource;
import io.github.melswg.worldmind.api.gamecontext.v1.GameContextWorldContext;
import io.github.melswg.worldmind.core.conversation.AddressingSignal;
import io.github.melswg.worldmind.core.conversation.ChatBatchSealReason;
import io.github.melswg.worldmind.core.conversation.ObservedPublicChatMessage;
import io.github.melswg.worldmind.core.conversation.SealedChatBatch;
import io.github.melswg.worldmind.core.conversation.ServerRequester;
import io.github.melswg.worldmind.core.conversation.UntrustedContext;
import io.github.melswg.worldmind.core.conversation.WorldIdentity;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class GameContextExtensionRuntimeTest {
    @Test
    void ownsRegistrationLifecycleAndDeterministicallyAppendsStructuredSources() {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        GameContextExtensionRuntime runtime = new GameContextExtensionRuntime(
            worker, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
        );
        try {
            List<String> events = new ArrayList<>();
            RecordingProvider zeta = new RecordingProvider("zeta", events, "late", "z");
            RecordingProvider alpha = new RecordingProvider("alpha", events, "early", "a");
            runtime.registrarFor("zeta").register(zeta);
            GameContextRegistration registration = runtime.registrarFor("alpha").register(alpha);

            join(runtime.onWorldLoad("minecraft:the_nether"));
            join(runtime.onServerStart(new GameContextServerContext("server-one")));

            assertEquals(List.of(
                "alpha:start", "zeta:start", "alpha:load:minecraft:the_nether", "zeta:load:minecraft:the_nether"
            ), events);

            List<UntrustedContext> context = join(runtime.resolve(batch()));
            assertEquals(List.of(
                "vanilla-game-context", "extension-game-context:alpha:early#a", "extension-game-context:zeta:late#z"
            ), context.stream().map(UntrustedContext::source).toList());
            assertEquals(List.of("clear", "alpha", "zeta"), context.stream().map(UntrustedContext::content).toList());

            registration.close();
            registration.close();
            assertFalse(registration.active());
            alpha.cleanupCompleted.join();
            assertEquals(1, alpha.cleanups);
            assertEquals(List.of("zeta"), join(runtime.resolve(batch())).stream().skip(1).map(UntrustedContext::content).toList());

            join(runtime.shutdown());
            assertEquals(1, zeta.cleanups);
            assertTrue(events.contains("zeta:shutdown"));
            assertTrue(events.contains("zeta:unload:minecraft:the_nether"));
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    void rejectsOwnershipMismatchAndDuplicateSourceWithoutSelectingAnArbitraryProvider() {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        GameContextExtensionRuntime runtime = new GameContextExtensionRuntime(worker, Clock.systemUTC());
        try {
            RecordingProvider alpha = new RecordingProvider("alpha", new ArrayList<>(), "context", "a");
            runtime.registrarFor("alpha").register(alpha);
            assertThrows(IllegalArgumentException.class, () -> runtime.registrarFor("other").register(alpha));
            assertThrows(IllegalArgumentException.class, () -> runtime.registrarFor("alpha").register(alpha));
        } finally {
            runtime.close();
            worker.shutdownNow();
        }
    }

    private static SealedChatBatch batch() {
        return new SealedChatBatch(
            new WorldIdentity("world-test"),
            List.of(new ObservedPublicChatMessage(
                7,
                new ServerRequester(UUID.fromString("7d4cb4c0-063a-4e71-b8f8-8a9de813bcde"), "Mira"),
                "Aster, are you there?",
                AddressingSignal.EXACT,
                Instant.EPOCH,
                List.of(new UntrustedContext("vanilla-game-context", "clear"))
            )),
            ChatBatchSealReason.ADDRESSING_SIGNAL,
            List.of(new UntrustedContext("vanilla-game-context", "clear"))
        );
    }

    private static <T> T join(java.util.concurrent.CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }

    private static final class RecordingProvider implements GameContextProvider {
        private final GameContextSource source;
        private final List<String> events;
        private final String key;
        private final String value;
        private final CompletableFuture<Void> cleanupCompleted = new CompletableFuture<>();
        private int cleanups;

        private RecordingProvider(String namespace, List<String> events, String key, String value) {
            this.source = new GameContextSource(namespace, key);
            this.events = events;
            this.key = key;
            this.value = value;
        }

        @Override public GameContextSource source() { return source; }
        @Override public CompletableFuture<Void> onServerStart(GameContextServerContext server) {
            events.add(source.namespace() + ":start");
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<Void> onWorldLoad(GameContextWorldContext world) {
            events.add(source.namespace() + ":load:" + world.dimensionId());
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<Void> onWorldUnload(GameContextWorldContext world) {
            events.add(source.namespace() + ":unload:" + world.dimensionId());
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<Void> onServerShutdown(GameContextServerContext server) {
            events.add(source.namespace() + ":shutdown");
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<Void> onCleanup() {
            cleanups++;
            cleanupCompleted.complete(null);
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<GameContextResult> provide(GameContextRequest request) {
            return CompletableFuture.completedFuture(new GameContextResult(List.of(new GameContextEntry(value, source.namespace()))));
        }
    }
}
