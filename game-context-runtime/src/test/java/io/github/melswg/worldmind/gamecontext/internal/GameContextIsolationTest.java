package io.github.melswg.worldmind.gamecontext.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melswg.worldmind.api.gamecontext.v1.GameContextEntry;
import io.github.melswg.worldmind.api.gamecontext.v1.GameContextProvider;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class GameContextIsolationTest {
    @Test
    void timesOutAndQuarantinesOnlyTheHangingProviderWhileKeepingHealthyContext() throws Exception {
        Fixture fixture = fixture();
        CountDownLatch release = new CountDownLatch(1);
        try {
            AtomicInteger hangingCalls = new AtomicInteger();
            fixture.runtime.registrarFor("healthy").register(provider("healthy", request -> result("weather", "clear")));
            fixture.runtime.registrarFor("hanging").register(provider("hanging", request -> {
                hangingCalls.incrementAndGet();
                awaitIgnoringInterrupts(release);
                return result("never", "returned");
            }));
            start(fixture);

            List<UntrustedContext> resolved = fixture.runtime.resolve(batch()).toCompletableFuture().get(1, TimeUnit.SECONDS);
            assertEquals(List.of("clear", "clear"), resolved.stream().map(UntrustedContext::content).toList());
            assertEquals(List.of("vanilla-game-context", "extension-game-context:healthy:state#weather"),
                resolved.stream().map(UntrustedContext::source).toList());
            GameContextRuntimeSnapshot snapshot = fixture.runtime.snapshot();
            assertEquals(1, snapshot.active());
            assertEquals(1, snapshot.quarantined());
            assertEquals("hanging:state", snapshot.latestDiagnostic().orElseThrow().source().canonicalName());
            assertEquals(GameContextDiagnosticCode.TIMEOUT, snapshot.latestDiagnostic().orElseThrow().code());

            fixture.runtime.resolve(batch()).toCompletableFuture().get(1, TimeUnit.SECONDS);
            assertEquals(1, hangingCalls.get(), "A quarantined provider must never be invoked again this server session.");
        } finally {
            release.countDown();
            fixture.close();
        }
    }

    @Test
    void isolatesThrowsAndRejectsMalformedOrOversizedResultsWithoutLeakingTheirContents() throws Exception {
        Fixture fixture = fixture();
        try {
            AtomicInteger throwingCalls = new AtomicInteger();
            fixture.runtime.registrarFor("healthy").register(provider("healthy", request -> result("zeta", "kept")));
            fixture.runtime.registrarFor("throwing").register(provider("throwing", request -> {
                throwingCalls.incrementAndGet();
                throw new IllegalStateException("secret-token /absolute/path");
            }));
            fixture.runtime.registrarFor("malformed").register(provider("malformed", request -> new GameContextResult(List.of(
                new GameContextEntry("duplicate", "one"), new GameContextEntry("duplicate", "two")
            ))));
            fixture.runtime.registrarFor("oversized").register(provider("oversized", request -> result("value", "x".repeat(513))));
            start(fixture);

            List<UntrustedContext> resolved = fixture.runtime.resolve(batch()).toCompletableFuture().get(1, TimeUnit.SECONDS);
            assertEquals(List.of("clear", "kept"), resolved.stream().map(UntrustedContext::content).toList());
            assertEquals(0, fixture.runtime.snapshot().quarantined());
            assertFalse(fixture.runtime.snapshot().latestDiagnostic().orElseThrow().toString().contains("secret-token"));

            fixture.runtime.resolve(batch()).toCompletableFuture().get(1, TimeUnit.SECONDS);
            assertEquals(2, throwingCalls.get(), "A single synchronous exception is isolated to its invocation.");
        } finally {
            fixture.close();
        }
    }

    @Test
    void normalizesOnlySafeNfcLineFeedsBeforeSourceAttribution() throws Exception {
        Fixture fixture = fixture();
        try {
            fixture.runtime.registrarFor("normal").register(provider("normal", request -> result(
                "state", "e\u0301\r\n\u202Eclear\u0007"
            )));
            start(fixture);

            List<UntrustedContext> resolved = fixture.runtime.resolve(batch()).toCompletableFuture().get(1, TimeUnit.SECONDS);
            assertEquals("é\nclear", resolved.get(1).content());
            assertEquals("extension-game-context:normal:state#state", resolved.get(1).source());
        } finally {
            fixture.close();
        }
    }

    @Test
    void rejectsAnOversizedProviderResultAsOneSafeDiagnostic() throws Exception {
        Fixture fixture = fixture();
        try {
            fixture.runtime.registrarFor("large").register(provider("large", request -> result("state", "x".repeat(513))));
            start(fixture);

            assertEquals(List.of("clear"), fixture.runtime.resolve(batch()).toCompletableFuture().get(1, TimeUnit.SECONDS)
                .stream().map(UntrustedContext::content).toList());
            assertEquals(GameContextDiagnosticCode.OVERSIZED_RESULT,
                fixture.runtime.snapshot().latestDiagnostic().orElseThrow().code());
            assertEquals("large:state", fixture.runtime.snapshot().latestDiagnostic().orElseThrow().source().canonicalName());
        } finally {
            fixture.close();
        }
    }

    private static void start(Fixture fixture) throws Exception {
        fixture.runtime.onServerStart(new GameContextServerContext("test-server")).toCompletableFuture().get(1, TimeUnit.SECONDS);
    }

    private static GameContextProvider provider(
        String namespace,
        java.util.function.Function<GameContextRequest, GameContextResult> callback
    ) {
        return new GameContextProvider() {
            private final GameContextSource source = new GameContextSource(namespace, "state");

            @Override public GameContextSource source() { return source; }

            @Override public CompletableFuture<GameContextResult> provide(GameContextRequest request) {
                return CompletableFuture.completedFuture(callback.apply(request));
            }
        };
    }

    private static GameContextResult result(String key, String value) {
        return new GameContextResult(List.of(new GameContextEntry(key, value)));
    }

    private static SealedChatBatch batch() {
        return new SealedChatBatch(new WorldIdentity("isolated-world"), List.of(new ObservedPublicChatMessage(
            1,
            new ServerRequester(UUID.fromString("d6774384-d133-41ca-8758-0e3d5e90e4a3"), "Mira"),
            "Aster, help", AddressingSignal.EXACT, Instant.EPOCH,
            List.of(new UntrustedContext("vanilla-game-context", "clear"))
        )), ChatBatchSealReason.ADDRESSING_SIGNAL, List.of(new UntrustedContext("vanilla-game-context", "clear")));
    }

    private static void awaitIgnoringInterrupts(CountDownLatch release) {
        boolean interrupted = false;
        while (release.getCount() > 0) {
            try {
                release.await();
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static Fixture fixture() {
        ExecutorService workers = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "test-game-context-worker");
            thread.setDaemon(true);
            return thread;
        });
        ScheduledExecutorService timeouts = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "test-game-context-timeout");
            thread.setDaemon(true);
            return thread;
        });
        return new Fixture(
            new GameContextExtensionRuntime(
                workers, timeouts, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                new GameContextInvocationPolicy(Duration.ofMillis(40))
            ), workers, timeouts
        );
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
