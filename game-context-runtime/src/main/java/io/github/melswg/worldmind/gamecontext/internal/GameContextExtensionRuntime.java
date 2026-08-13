package io.github.melswg.worldmind.gamecontext.internal;

import io.github.melswg.worldmind.api.gamecontext.v1.GameContextEntry;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Internal Worldmind-owned lifecycle and collection runtime. It is intentionally
 * independent of Fabric and Minecraft object types.
 */
public final class GameContextExtensionRuntime implements CurrentGameContextResolver, AutoCloseable {
    private final ExecutorService worker;
    private final Clock clock;
    private final GameContextRegistrationCatalog catalog;
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final Set<String> cleanedSources = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final NavigableSet<String> loadedDimensions = new TreeSet<>();
    private volatile GameContextServerContext server;
    private volatile long generation;

    public static GameContextExtensionRuntime create() {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "worldmind-game-context");
            thread.setDaemon(true);
            return thread;
        };
        return new GameContextExtensionRuntime(Executors.newSingleThreadExecutor(factory), Clock.systemUTC());
    }

    public GameContextExtensionRuntime(ExecutorService worker, Clock clock) {
        this.worker = Objects.requireNonNull(worker, "worker");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.catalog = new GameContextRegistrationCatalog(this::cleanupProvider);
    }

    public GameContextRegistrar registrarFor(String owningModId) {
        return catalog.registrarFor(owningModId);
    }

    public CompletionStage<Void> onServerStart(GameContextServerContext server) {
        this.server = Objects.requireNonNull(server, "server");
        active.set(true);
        List<GameContextRegistrationCatalog.RegisteredProvider> providers = catalog.activeProviders();
        CompletionStage<Void> started = invokeAll(providers, provider -> provider.onServerStart(server));
        return started.thenCompose(ignored -> {
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
            : invokeAll(catalog.activeProviders(), provider -> provider.onWorldLoad(new GameContextWorldContext(current, dimension)));
    }

    public CompletionStage<Void> onWorldUnload(String dimensionId) {
        String dimension = requireDimension(dimensionId);
        synchronized (loadedDimensions) {
            loadedDimensions.remove(dimension);
        }
        GameContextServerContext current = server;
        return current == null
            ? CompletableFuture.completedFuture(null)
            : invokeAll(catalog.activeProviders(), provider -> provider.onWorldUnload(new GameContextWorldContext(current, dimension)));
    }

    public CompletionStage<Void> onReload(long newGeneration) {
        GameContextServerContext current = server;
        if (current == null) return CompletableFuture.completedFuture(null);
        if (newGeneration <= generation) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("reload generation must increase."));
        }
        generation = newGeneration;
        return invokeAll(catalog.activeProviders(), provider -> provider.onReload(new GameContextReloadContext(current, newGeneration)));
    }

    @Override
    public CompletionStage<List<UntrustedContext>> resolve(SealedChatBatch batch) {
        Objects.requireNonNull(batch, "batch");
        List<UntrustedContext> vanilla = batch.currentContextSnapshot();
        GameContextServerContext current = server;
        if (!active.get() || current == null) return CompletableFuture.completedFuture(vanilla);
        GameContextRequest request = new GameContextRequest(
            current,
            batch.worldIdentity().stableId(),
            batch.messages().get(0).sequence(),
            batch.messages().get(batch.messages().size() - 1).sequence(),
            batch.messages().size(),
            clock.instant()
        );
        List<GameContextRegistrationCatalog.RegisteredProvider> providers = catalog.activeProviders();
        List<CompletionStage<List<UntrustedContext>>> collected = providers.stream()
            .map(provider -> collect(provider, request))
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

    /** Generates an opaque session identity for the Fabric lifecycle adapter. */
    public static GameContextServerContext newServerContext() {
        return new GameContextServerContext("session-" + UUID.randomUUID());
    }

    public CompletionStage<Void> shutdown() {
        if (!active.compareAndSet(true, false)) return CompletableFuture.completedFuture(null);
        GameContextServerContext current = server;
        List<GameContextRegistrationCatalog.RegisteredProvider> providers = catalog.activeProviders();
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
            worker.shutdownNow();
            server = null;
        });
    }

    @Override
    public void close() {
        shutdown();
    }

    private CompletionStage<List<UntrustedContext>> collect(
        GameContextRegistrationCatalog.RegisteredProvider registered,
        GameContextRequest request
    ) {
        if (!registered.active()) return CompletableFuture.completedFuture(List.of());
        return invoke(() -> registered.provider().provide(request)).handle((result, failure) -> {
            if (failure != null || result == null || !registered.active() || !active.get()) return List.of();
            try {
                return fragments(registered.source(), result);
            } catch (RuntimeException malformed) {
                return List.of();
            }
        });
    }

    private static List<UntrustedContext> fragments(GameContextSource source, GameContextResult result) {
        return result.entries().stream()
            .sorted(Comparator.comparing(GameContextEntry::key))
            .map(entry -> new UntrustedContext(
                "extension-game-context:" + source.canonicalName() + "#" + entry.key(),
                entry.value()
            ))
            .toList();
    }

    private CompletionStage<Void> invokeAll(
        List<GameContextRegistrationCatalog.RegisteredProvider> providers,
        Function<GameContextProvider, CompletionStage<Void>> callback
    ) {
        CompletionStage<Void> result = CompletableFuture.completedFuture(null);
        for (GameContextRegistrationCatalog.RegisteredProvider registered : providers) {
            result = result.thenCompose(ignored -> !registered.active()
                ? CompletableFuture.completedFuture(null)
                : invoke(() -> callback.apply(registered.provider())).handle((value, failure) -> null));
        }
        return result;
    }

    private CompletionStage<Void> cleanupAll(List<GameContextRegistrationCatalog.RegisteredProvider> providers) {
        CompletionStage<Void> result = CompletableFuture.completedFuture(null);
        for (GameContextRegistrationCatalog.RegisteredProvider provider : providers) {
            result = result.thenCompose(ignored -> cleanupProviderStage(provider));
        }
        return result;
    }

    private void cleanupProvider(GameContextRegistrationCatalog.RegisteredProvider provider) {
        cleanupProviderStage(provider);
    }

    private CompletionStage<Void> cleanupProviderStage(GameContextRegistrationCatalog.RegisteredProvider provider) {
        if (!cleanedSources.add(provider.source().canonicalName())) return CompletableFuture.completedFuture(null);
        return invoke(() -> provider.provider().onCleanup()).handle((ignored, failure) -> null);
    }

    private <T> CompletionStage<T> invoke(Supplier<CompletionStage<T>> callback) {
        CompletableFuture<T> completed = new CompletableFuture<>();
        try {
            worker.execute(() -> {
                try {
                    CompletionStage<T> stage = callback.get();
                    if (stage == null) {
                        completed.completeExceptionally(new IllegalStateException("Game-context callback returned no completion stage."));
                        return;
                    }
                    stage.whenComplete((value, failure) -> {
                        if (failure == null) completed.complete(value); else completed.completeExceptionally(failure);
                    });
                } catch (RuntimeException failure) {
                    completed.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException failure) {
            completed.completeExceptionally(failure);
        }
        return completed;
    }

    private static String requireDimension(String dimension) {
        Objects.requireNonNull(dimension, "dimensionId");
        if (dimension.isBlank()) throw new IllegalArgumentException("dimensionId must not be blank.");
        return dimension;
    }
}
