package io.github.melswg.worldmind.gamecontext.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melswg.worldmind.api.gamecontext.v1.GameContextEntry;
import io.github.melswg.worldmind.api.gamecontext.v1.GameContextProvider;
import io.github.melswg.worldmind.api.gamecontext.v1.GameContextReloadContext;
import io.github.melswg.worldmind.api.gamecontext.v1.GameContextRequest;
import io.github.melswg.worldmind.api.gamecontext.v1.GameContextResult;
import io.github.melswg.worldmind.api.gamecontext.v1.GameContextServerContext;
import io.github.melswg.worldmind.api.gamecontext.v1.GameContextSource;
import io.github.melswg.worldmind.core.conversation.AddressingSignal;
import io.github.melswg.worldmind.core.conversation.ChatBatchSealReason;
import io.github.melswg.worldmind.core.conversation.ObservedPublicChatMessage;
import io.github.melswg.worldmind.core.conversation.SealedChatBatch;
import io.github.melswg.worldmind.core.conversation.ServerRequester;
import io.github.melswg.worldmind.core.conversation.UntrustedContext;
import io.github.melswg.worldmind.core.conversation.WorldIdentity;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class GameContextLifecycleIsolationTest {
    @Test
    void reloadCancelsLateResultsAndAllowsOnlyNewGenerationContext() throws Exception {
        Fixture fixture = fixture();
        try {
            CompletableFuture<GameContextResult> late = new CompletableFuture<>();
            fixture.runtime.registrarFor("late").register(new GameContextProvider() {
                private final GameContextSource source = new GameContextSource("late", "state");
                @Override public GameContextSource source() { return source; }
                @Override public java.util.concurrent.CompletionStage<GameContextResult> provide(GameContextRequest request) {
                    return late.minimalCompletionStage();
                }
            });
            fixture.runtime.onServerStart(new GameContextServerContext("server")).toCompletableFuture().get(1, TimeUnit.SECONDS);

            CompletableFuture<List<UntrustedContext>> first = fixture.runtime.resolve(batch()).toCompletableFuture();
            fixture.runtime.onReload(1).toCompletableFuture().get(1, TimeUnit.SECONDS);
            late.complete(new GameContextResult(List.of(new GameContextEntry("state", "stale"))));

            assertEquals(List.of("clear"), first.get(1, TimeUnit.SECONDS).stream().map(UntrustedContext::content).toList());
            assertEquals(List.of("clear", "stale"), fixture.runtime.resolve(batch()).toCompletableFuture().get(1, TimeUnit.SECONDS)
                .stream().map(UntrustedContext::content).toList());
        } finally {
            fixture.close();
        }
    }

    @Test
    void lifecycleFailureQuarantinesProviderWithoutPreventingHealthyProvidersFromReloading() throws Exception {
        Fixture fixture = fixture();
        try {
            fixture.runtime.registrarFor("healthy").register(provider("healthy"));
            fixture.runtime.registrarFor("broken").register(new GameContextProvider() {
                private final GameContextSource source = new GameContextSource("broken", "state");
                @Override public GameContextSource source() { return source; }
                @Override public CompletableFuture<Void> onReload(GameContextReloadContext reload) {
                    return CompletableFuture.failedFuture(new IllegalStateException("secret /private/absolute-path"));
                }
                @Override public CompletableFuture<GameContextResult> provide(GameContextRequest request) {
                    return CompletableFuture.completedFuture(new GameContextResult(List.of(new GameContextEntry("state", "must-not-run"))));
                }
            });
            fixture.runtime.onServerStart(new GameContextServerContext("server")).toCompletableFuture().get(1, TimeUnit.SECONDS);
            fixture.runtime.onReload(1).toCompletableFuture().get(1, TimeUnit.SECONDS);

            GameContextRuntimeSnapshot snapshot = fixture.runtime.snapshot();
            assertEquals(1, snapshot.quarantined());
            assertEquals("broken:state", snapshot.latestDiagnostic().orElseThrow().source().canonicalName());
            assertEquals(GameContextDiagnosticCode.INVOCATION_FAILURE, snapshot.latestDiagnostic().orElseThrow().code());
            assertEquals(List.of("clear", "healthy"), fixture.runtime.resolve(batch()).toCompletableFuture().get(1, TimeUnit.SECONDS)
                .stream().map(UntrustedContext::content).toList());
            assertTrue(snapshot.latestDiagnostic().orElseThrow().toString().contains("INVOCATION_FAILURE"));
            assertTrue(!snapshot.latestDiagnostic().orElseThrow().toString().contains("secret"));
        } finally {
            fixture.close();
        }
    }

    private static GameContextProvider provider(String namespace) {
        return new GameContextProvider() {
            private final GameContextSource source = new GameContextSource(namespace, "state");
            @Override public GameContextSource source() { return source; }
            @Override public CompletableFuture<GameContextResult> provide(GameContextRequest request) {
                return CompletableFuture.completedFuture(new GameContextResult(List.of(new GameContextEntry("state", namespace))));
            }
        };
    }

    private static SealedChatBatch batch() {
        return new SealedChatBatch(new WorldIdentity("reload-world"), List.of(new ObservedPublicChatMessage(
            1,
            new ServerRequester(UUID.fromString("50d2cfe5-4bfb-40cd-9786-ec4b3767b830"), "Mira"),
            "Aster", AddressingSignal.EXACT, Instant.EPOCH,
            List.of(new UntrustedContext("vanilla-game-context", "clear"))
        )), ChatBatchSealReason.ADDRESSING_SIGNAL, List.of(new UntrustedContext("vanilla-game-context", "clear")));
    }

    private static Fixture fixture() {
        ExecutorService workers = Executors.newFixedThreadPool(3);
        ScheduledExecutorService timeouts = Executors.newSingleThreadScheduledExecutor();
        return new Fixture(new GameContextExtensionRuntime(
            workers, timeouts, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), new GameContextInvocationPolicy(Duration.ofMillis(80))
        ), workers, timeouts);
    }

    private record Fixture(GameContextExtensionRuntime runtime, ExecutorService workers, ScheduledExecutorService timeouts)
        implements AutoCloseable {
        @Override public void close() {
            runtime.close();
            workers.shutdownNow();
            timeouts.shutdownNow();
        }
    }
}
