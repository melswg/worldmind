package io.github.melswg.worldmind.gamecontext.internal;

import io.github.melswg.worldmind.api.gamecontext.v1.GameContextEntry;
import io.github.melswg.worldmind.api.gamecontext.v1.GameContextLimits;
import io.github.melswg.worldmind.api.gamecontext.v1.GameContextProvider;
import io.github.melswg.worldmind.api.gamecontext.v1.GameContextRegistrar;
import io.github.melswg.worldmind.api.gamecontext.v1.GameContextReloadContext;
import io.github.melswg.worldmind.api.gamecontext.v1.GameContextRequest;
import io.github.melswg.worldmind.api.gamecontext.v1.GameContextResult;
import io.github.melswg.worldmind.api.gamecontext.v1.GameContextServerContext;
import io.github.melswg.worldmind.api.gamecontext.v1.GameContextSource;
import io.github.melswg.worldmind.api.gamecontext.v1.GameContextWorldContext;
import io.github.melswg.worldmind.core.conversation.CurrentGameContextResolver;
import io.github.melswg.worldmind.core.conversation.SealedChatBatch;
import io.github.melswg.worldmind.core.conversation.UntrustedContext;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Internal Worldmind-owned lifecycle and collection runtime. Every provider
 * invocation runs on bounded daemon workers, never on the Minecraft server thread.
 */
public final class GameContextExtensionRuntime implements CurrentGameContextResolver, AutoCloseable {
    private final ExecutorService workers;
    private final ScheduledExecutorService timeouts;
    private final Clock clock;
    private final GameContextInvocationPolicy invocationPolicy;
    private final GameContextRegistrationCatalog catalog;
    private final GameContextDiagnostics diagnostics = new GameContextDiagnostics();
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final Set<String> cleanedSources = ConcurrentHashMap.newKeySet();
    private final Set<Invocation<?>> invocations = ConcurrentHashMap.newKeySet();
    private final NavigableSet<String> loadedDimensions = new TreeSet<>();
    private volatile GameContextServerContext server;
    private volatile long generation;

    public static GameContextExtensionRuntime create() {
        ThreadFactory workerFactory = daemonFactory("worldmind-game-context");
        return new GameContextExtensionRuntime(
            Executors.newFixedThreadPool(GameContextLimits.MAX_PROVIDERS, workerFactory),
            Executors.newSingleThreadScheduledExecutor(daemonFactory("worldmind-game-context-timeout")),
            Clock.systemUTC(),
            GameContextInvocationPolicy.v01()
        );
    }

    public GameContextExtensionRuntime(ExecutorService workers, Clock clock) {
        this(
            workers,
            Executors.newSingleThreadScheduledExecutor(daemonFactory("worldmind-game-context-timeout-test")),
            clock,
            GameContextInvocationPolicy.v01()
        );
    }

    GameContextExtensionRuntime(
        ExecutorService workers,
        ScheduledExecutorService timeouts,
        Clock clock,
        GameContextInvocationPolicy invocationPolicy
    ) {
        this.workers = Objects.requireNonNull(workers, "workers");
        this.timeouts = Objects.requireNonNull(timeouts, "timeouts");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.invocationPolicy = Objects.requireNonNull(invocationPolicy, "invocationPolicy");
        this.catalog = new GameContextRegistrationCatalog(this::cleanupProvider);
    }

    public GameContextRegistrar registrarFor(String owningModId) {
        return catalog.registrarFor(owningModId);
    }

    public CompletionStage<Void> onServerStart(GameContextServerContext server) {
        this.server = Objects.requireNonNull(server, "server");
        active.set(true);
        List<GameContextRegistrationCatalog.RegisteredProvider> providers = catalog.providers();
        return invokeAll(providers, provider -> provider.onServerStart(server)).thenCompose(ignored -> {
            List<String> dimensions;
            synchronized (loadedDimensions) {
                dimensions = List.copyOf(loadedDimensions);
            }
            CompletionStage<Void> lifecycle = CompletableFuture.completedFuture(null);
            for (String dimension : dimensions) {
                GameContextWorldContext world = new GameContextWorldContext(server, dimension);
                lifecycle = lifecycle.thenCompose(ignoredAgain -> invokeAll(providers, provider -> provider.onWorldLoad(world)));
            }
            return lifecycle;
        });
    }

    public CompletionStage<Void> onWorldLoad(String dimensionId) {
        String dimension = requireDimension(dimensionId);
        synchronized (loadedDimensions) {
            loadedDimensions.add(dimension);
        }
        GameContextServerContext current = server;
        return current == null || !active.get()
            ? CompletableFuture.completedFuture(null)
            : invokeAll(catalog.providers(), provider -> provider.onWorldLoad(new GameContextWorldContext(current, dimension)));
    }

    public CompletionStage<Void> onWorldUnload(String dimensionId) {
        String dimension = requireDimension(dimensionId);
        synchronized (loadedDimensions) {
            loadedDimensions.remove(dimension);
        }
        GameContextServerContext current = server;
        return current == null
            ? CompletableFuture.completedFuture(null)
            : invokeAll(catalog.providers(), provider -> provider.onWorldUnload(new GameContextWorldContext(current, dimension)));
    }

    public CompletionStage<Void> onReload(long newGeneration) {
        GameContextServerContext current = server;
        if (current == null) return CompletableFuture.completedFuture(null);
        if (newGeneration <= generation) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("reload generation must increase."));
        }
        generation = newGeneration;
        cancelActiveInvocations();
        return invokeAll(catalog.providers(), provider -> provider.onReload(new GameContextReloadContext(current, newGeneration)));
    }

    @Override
    public CompletionStage<List<UntrustedContext>> resolve(SealedChatBatch batch) {
        Objects.requireNonNull(batch, "batch");
        List<UntrustedContext> vanilla = batch.currentContextSnapshot();
        GameContextServerContext current = server;
        long requestGeneration = generation;
        if (!active.get() || current == null) return CompletableFuture.completedFuture(vanilla);
        GameContextRequest request = new GameContextRequest(
            current,
            batch.worldIdentity().stableId(),
            batch.messages().get(0).sequence(),
            batch.messages().get(batch.messages().size() - 1).sequence(),
            batch.messages().size(),
            clock.instant()
        );
        List<CompletionStage<List<UntrustedContext>>> collected = catalog.providers().stream()
            .map(provider -> collect(provider, request, requestGeneration))
            .toList();
        CompletableFuture<?>[] completions = collected.stream().map(CompletionStage::toCompletableFuture)
            .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(completions).thenApply(ignored -> {
            List<UntrustedContext> combined = new ArrayList<>(vanilla);
            for (CompletionStage<List<UntrustedContext>> stage : collected) {
                combined.addAll(stage.toCompletableFuture().getNow(List.of()));
            }
            return List.copyOf(combined);
        });
    }

    /** Returns only content-free counters and the last safe provider diagnostic. */
    public GameContextRuntimeSnapshot snapshot() {
        List<GameContextRegistrationCatalog.RegisteredProvider> providers = catalog.providers();
        int registered = providers.size();
        int quarantined = (int) providers.stream().filter(GameContextRegistrationCatalog.RegisteredProvider::quarantined).count();
        int inFlight = (int) providers.stream().filter(GameContextRegistrationCatalog.RegisteredProvider::inFlight).count();
        int usable = (int) providers.stream().filter(provider -> provider.active() && !provider.quarantined()).count();
        return new GameContextRuntimeSnapshot(registered, usable, quarantined, inFlight, diagnostics.latest());
    }

    /** Generates an opaque session identity for the Fabric lifecycle adapter. */
    public static GameContextServerContext newServerContext() {
        return new GameContextServerContext("session-" + UUID.randomUUID());
    }

    public CompletionStage<Void> shutdown() {
        if (!active.compareAndSet(true, false)) return CompletableFuture.completedFuture(null);
        ++generation;
        cancelActiveInvocations();
        GameContextServerContext current = server;
        List<GameContextRegistrationCatalog.RegisteredProvider> providers = catalog.providers();
        List<String> dimensions;
        synchronized (loadedDimensions) {
            dimensions = List.copyOf(loadedDimensions);
            loadedDimensions.clear();
        }
        CompletionStage<Void> lifecycle = CompletableFuture.completedFuture(null);
        if (current != null) {
            for (String dimension : dimensions) {
                GameContextWorldContext world = new GameContextWorldContext(current, dimension);
                lifecycle = lifecycle.thenCompose(ignored -> invokeAll(providers, provider -> provider.onWorldUnload(world)));
            }
            lifecycle = lifecycle.thenCompose(ignored -> invokeAll(providers, provider -> provider.onServerShutdown(current)));
        }
        return lifecycle.thenCompose(ignored -> cleanupAll(providers)).whenComplete((ignored, failure) -> {
            catalog.closeAll();
            workers.shutdownNow();
            timeouts.shutdownNow();
            server = null;
        });
    }

    @Override
    public void close() {
        shutdown();
    }

    private CompletionStage<List<UntrustedContext>> collect(
        GameContextRegistrationCatalog.RegisteredProvider registered,
        GameContextRequest request,
        long requestGeneration
    ) {
        return invoke(registered, () -> registered.provider().provide(request), false, false).handle((result, failure) -> {
            if (failure != null || !registered.active() || !active.get() || generation != requestGeneration) return List.of();
            try {
                return fragments(registered.source(), GameContextNormalizer.normalize(result));
            } catch (GameContextNormalizer.ValidationFailure malformed) {
                diagnostics.record(new GameContextDiagnostic(registered.source(), malformed.code()));
                return List.of();
            } catch (RuntimeException malformed) {
                diagnostics.record(new GameContextDiagnostic(registered.source(), GameContextDiagnosticCode.MALFORMED_RESULT));
                return List.of();
            }
        });
    }

    private static List<UntrustedContext> fragments(GameContextSource source, List<GameContextEntry> entries) {
        return entries.stream().map(entry -> new UntrustedContext(
            "extension-game-context:" + source.canonicalName() + "#" + entry.key(), entry.value()
        )).toList();
    }

    private CompletionStage<Void> invokeAll(
        List<GameContextRegistrationCatalog.RegisteredProvider> providers,
        Function<GameContextProvider, CompletionStage<Void>> callback
    ) {
        CompletableFuture<?>[] callbacks = providers.stream()
            .filter(provider -> provider.active() && !provider.quarantined())
            .map(provider -> invoke(provider, () -> callback.apply(provider.provider()), true, false)
                .handle((ignored, failure) -> null).toCompletableFuture())
            .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(callbacks);
    }

    private CompletionStage<Void> cleanupAll(List<GameContextRegistrationCatalog.RegisteredProvider> providers) {
        CompletableFuture<?>[] cleanups = providers.stream()
            .map(this::cleanupProviderStage)
            .map(CompletionStage::toCompletableFuture)
            .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(cleanups);
    }

    private void cleanupProvider(GameContextRegistrationCatalog.RegisteredProvider provider) {
        cleanupProviderStage(provider);
    }

    private CompletionStage<Void> cleanupProviderStage(GameContextRegistrationCatalog.RegisteredProvider provider) {
        if (!cleanedSources.add(provider.source().canonicalName())) return CompletableFuture.completedFuture(null);
        return invoke(provider, () -> provider.provider().onCleanup(), false, true).handle((ignored, failure) -> null);
    }

    private <T> CompletionStage<T> invoke(
        GameContextRegistrationCatalog.RegisteredProvider provider,
        Supplier<CompletionStage<T>> callback,
        boolean lifecycle,
        boolean cleanup
    ) {
        if (!provider.beginInvocation(cleanup)) {
            return CompletableFuture.failedFuture(new InvocationFailure(GameContextDiagnosticCode.CANCELLED));
        }
        Invocation<T> invocation = new Invocation<>(provider, lifecycle);
        invocations.add(invocation);
        try {
            workers.execute(() -> start(invocation, callback));
        } catch (RuntimeException failure) {
            invocation.fail(GameContextDiagnosticCode.INVOCATION_FAILURE, lifecycle, false);
        }
        return invocation.result;
    }

    private <T> void start(Invocation<T> invocation, Supplier<CompletionStage<T>> callback) {
        if (invocation.finished()) return;
        invocation.thread = Thread.currentThread();
        ScheduledFuture<?> timeout = timeouts.schedule(
            invocation::timeout,
            invocationPolicy.timeout().toMillis(),
            TimeUnit.MILLISECONDS
        );
        invocation.setTimeout(timeout);
        if (invocation.finished()) return;
        try {
            CompletionStage<T> stage = callback.get();
            invocation.callbackReturned = true;
            if (stage == null) {
                invocation.fail(GameContextDiagnosticCode.NULL_STAGE, invocation.lifecycle, false);
                return;
            }
            invocation.stage = stage;
            if (invocation.finished()) {
                cancelStage(stage);
                return;
            }
            stage.whenComplete((value, failure) -> {
                if (failure == null) invocation.complete(value);
                else invocation.fail(GameContextDiagnosticCode.INVOCATION_FAILURE, invocation.lifecycle, false);
            });
        } catch (RuntimeException failure) {
            invocation.fail(GameContextDiagnosticCode.INVOCATION_FAILURE, invocation.lifecycle, false);
        }
    }

    private void cancelActiveInvocations() {
        List.copyOf(invocations).forEach(Invocation::cancel);
    }

    private static void cancelStage(CompletionStage<?> stage) {
        try {
            stage.toCompletableFuture().cancel(true);
        } catch (RuntimeException ignored) {
            // External CompletionStage implementations are optional and best-effort only.
        }
    }

    private static String requireDimension(String dimension) {
        Objects.requireNonNull(dimension, "dimensionId");
        if (dimension.isBlank()) throw new IllegalArgumentException("dimensionId must not be blank.");
        return dimension;
    }

    private static ThreadFactory daemonFactory(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    private final class Invocation<T> {
        private final GameContextRegistrationCatalog.RegisteredProvider provider;
        private final boolean lifecycle;
        private final CompletableFuture<T> result = new CompletableFuture<>();
        private final AtomicBoolean done = new AtomicBoolean();
        private volatile CompletionStage<T> stage;
        private volatile ScheduledFuture<?> timeout;
        private volatile Thread thread;
        private volatile boolean callbackReturned;

        private Invocation(GameContextRegistrationCatalog.RegisteredProvider provider, boolean lifecycle) {
            this.provider = provider;
            this.lifecycle = lifecycle;
        }

        private boolean finished() {
            return done.get();
        }

        private void setTimeout(ScheduledFuture<?> timeout) {
            this.timeout = timeout;
            if (finished()) timeout.cancel(false);
        }

        private void complete(T value) {
            if (!done.compareAndSet(false, true)) return;
            finish();
            result.complete(value);
        }

        private void timeout() {
            if (!callbackReturned) provider.markAbandonedWorker();
            fail(GameContextDiagnosticCode.TIMEOUT, true, true);
        }

        private void cancel() {
            fail(GameContextDiagnosticCode.CANCELLED, false, true);
        }

        private void fail(GameContextDiagnosticCode code, boolean quarantine, boolean cancelStage) {
            if (!done.compareAndSet(false, true)) return;
            if (quarantine) provider.quarantine();
            diagnostics.record(new GameContextDiagnostic(provider.source(), code));
            if (cancelStage && stage != null) cancelStage(stage);
            Thread running = thread;
            if (cancelStage && running != null && !callbackReturned) running.interrupt();
            finish();
            result.completeExceptionally(new InvocationFailure(code));
        }

        private void finish() {
            ScheduledFuture<?> scheduled = timeout;
            if (scheduled != null) scheduled.cancel(false);
            invocations.remove(this);
            provider.finishInvocation();
        }
    }

    /** Internal failure type has no Throwable message to avoid accidental diagnostic leakage. */
    private static final class InvocationFailure extends RuntimeException {
        private InvocationFailure(GameContextDiagnosticCode code) {
            super(null, null, false, false);
        }
    }
}
