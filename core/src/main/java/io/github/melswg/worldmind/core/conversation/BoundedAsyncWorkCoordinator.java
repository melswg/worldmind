package io.github.melswg.worldmind.core.conversation;

import io.github.melswg.worldmind.core.configuration.RequestQueueConfiguration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * The one global bounded-work coordinator. It owns admission and tracks
 * asynchronous stage lifetimes while retaining strict submission order within
 * a world. Suppliers must only initiate non-blocking work.
 */
public final class BoundedAsyncWorkCoordinator implements AutoCloseable {
    private final RequestQueueConfiguration configuration;
    private final Map<WorldIdentity, WorldQueue> worlds = new HashMap<>();
    private final CompletableFuture<Void> terminated = new CompletableFuture<>();
    private int queued;
    private int inFlight;
    private boolean closed;

    public BoundedAsyncWorkCoordinator(RequestQueueConfiguration configuration) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
    }

    /** Never waits for storage, HTTP, a timer, or another owned job. */
    public <T> AsyncWorkSubmission<T> submit(
        WorldIdentity worldIdentity,
        long firstSequence,
        AsyncWorkKind kind,
        Supplier<? extends CompletionStage<T>> work
    ) {
        Objects.requireNonNull(worldIdentity, "worldIdentity");
        if (firstSequence <= 0) throw new IllegalArgumentException("firstSequence must be positive.");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(work, "work");
        QueuedWork<T> accepted;
        boolean startNow;
        synchronized (this) {
            if (closed) return rejected(AsyncWorkRejection.CLOSED);
            WorldQueue queue = worlds.computeIfAbsent(worldIdentity, ignored -> new WorldQueue());
            if (kind == AsyncWorkKind.CONVERSATION && firstSequence <= queue.lastConversationSequence) {
                throw new IllegalArgumentException("World work must be submitted in strictly increasing sequence order.");
            }
            accepted = new QueuedWork<>(worldIdentity, firstSequence, kind, work);
            startNow = queue.activeWork == null && inFlight < configuration.maxConcurrency() && queue.waiting.isEmpty();
            if (startNow) {
                queue.activeWork = accepted;
                inFlight++;
            } else {
                if (queued >= configuration.capacity()) return rejected(AsyncWorkRejection.CAPACITY);
                queue.waiting.addLast(accepted);
                queued++;
            }
            if (kind == AsyncWorkKind.CONVERSATION) queue.lastConversationSequence = firstSequence;
        }
        if (startNow) start(accepted);
        return new AsyncWorkSubmission<>(Optional.empty(), accepted.completion);
    }

    public synchronized AsyncWorkSnapshot snapshot() {
        return new AsyncWorkSnapshot(queued, inFlight, closed);
    }

    /** Completes after active stages have reached a terminal state and queued work was rejected. */
    public CompletionStage<Void> closeAsync() {
        List<QueuedWork<?>> cancelled = new ArrayList<>();
        List<CompletionStage<?>> activeStages = new ArrayList<>();
        synchronized (this) {
            if (closed) return terminated;
            closed = true;
            for (WorldQueue queue : worlds.values()) {
                while (!queue.waiting.isEmpty()) {
                    cancelled.add(queue.waiting.removeFirst());
                    queued--;
                }
                if (queue.activeWork != null && queue.activeWork.activeStage != null) {
                    activeStages.add(queue.activeWork.activeStage);
                }
            }
        }
        for (QueuedWork<?> work : cancelled) {
            work.completion.completeExceptionally(new AsyncWorkRejectedException(AsyncWorkRejection.CLOSED));
        }
        for (CompletionStage<?> stage : activeStages) {
            cancelBestEffort(stage);
        }
        synchronized (this) {
            completeTerminationIfDrained();
        }
        return terminated;
    }

    @Override
    public void close() {
        closeAsync();
    }

    private <T> AsyncWorkSubmission<T> rejected(AsyncWorkRejection rejection) {
        return new AsyncWorkSubmission<>(Optional.of(rejection), CompletableFuture.failedFuture(new AsyncWorkRejectedException(rejection)));
    }

    private <T> void start(QueuedWork<T> work) {
        CompletionStage<T> stage;
        try {
            stage = work.work.get();
            if (stage == null) throw new IllegalStateException("Async work returned no completion stage.");
        } catch (Throwable failure) {
            complete(work, null, failure);
            return;
        }
        boolean cancel;
        synchronized (this) {
            work.activeStage = stage;
            cancel = closed;
        }
        try {
            stage.whenComplete((value, failure) -> complete(work, value, failure));
        } catch (Throwable failure) {
            complete(work, null, failure);
            return;
        }
        if (cancel) cancelBestEffort(stage);
    }

    private <T> void complete(QueuedWork<T> completed, T value, Throwable failure) {
        QueuedWork<?> next = null;
        synchronized (this) {
            WorldQueue queue = worlds.get(completed.worldIdentity);
            if (queue == null || queue.activeWork != completed) return;
            queue.activeWork = null;
            inFlight--;
            if (!closed && !queue.waiting.isEmpty() && inFlight < configuration.maxConcurrency()) {
                next = queue.waiting.removeFirst();
                queued--;
                queue.activeWork = next;
                inFlight++;
            }
        }
        if (failure == null) completed.completion.complete(value); else completed.completion.completeExceptionally(failure);
        synchronized (this) {
            completeTerminationIfDrained();
        }
        if (next != null) startUnchecked(next);
        drainOtherWorlds();
    }

    private void drainOtherWorlds() {
        List<QueuedWork<?>> starts = new ArrayList<>();
        synchronized (this) {
            if (closed) return;
            for (WorldQueue queue : worlds.values()) {
                if (inFlight >= configuration.maxConcurrency()) break;
                if (queue.activeWork == null && !queue.waiting.isEmpty()) {
                    QueuedWork<?> next = queue.waiting.removeFirst();
                    queued--;
                    queue.activeWork = next;
                    inFlight++;
                    starts.add(next);
                }
            }
        }
        starts.forEach(this::startUnchecked);
    }

    private void completeTerminationIfDrained() {
        if (closed && queued == 0 && inFlight == 0) terminated.complete(null);
    }

    private static void cancelBestEffort(CompletionStage<?> stage) {
        try {
            stage.toCompletableFuture().cancel(true);
        } catch (RuntimeException ignored) {
            // Third-party stages need not expose cancellable work.
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void startUnchecked(QueuedWork<?> work) { start((QueuedWork) work); }

    private static final class WorldQueue {
        private long lastConversationSequence;
        private QueuedWork<?> activeWork;
        private final ArrayDeque<QueuedWork<?>> waiting = new ArrayDeque<>();
    }

    private static final class QueuedWork<T> {
        private final WorldIdentity worldIdentity;
        @SuppressWarnings("unused") private final long firstSequence;
        @SuppressWarnings("unused") private final AsyncWorkKind kind;
        private final Supplier<? extends CompletionStage<T>> work;
        private final CompletableFuture<T> completion = new CompletableFuture<>();
        private CompletionStage<?> activeStage;

        private QueuedWork(WorldIdentity worldIdentity, long firstSequence, AsyncWorkKind kind, Supplier<? extends CompletionStage<T>> work) {
            this.worldIdentity = worldIdentity;
            this.firstSequence = firstSequence;
            this.kind = kind;
            this.work = work;
        }
    }
}
