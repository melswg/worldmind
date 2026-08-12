package io.github.melswg.worldmind.fabric;

import io.github.melswg.worldmind.core.conversation.DelayedScheduler;
import io.github.melswg.worldmind.core.conversation.ScheduledWork;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/** Owns the non-server-thread timer used only by a logical-server chat runtime. */
final class FabricDelayedScheduler implements DelayedScheduler, AutoCloseable {
    private final ScheduledExecutorService executor;

    FabricDelayedScheduler() {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "worldmind-chat-batch-timer");
            thread.setDaemon(true);
            return thread;
        };
        executor = Executors.newSingleThreadScheduledExecutor(factory);
    }

    @Override
    public ScheduledWork schedule(Duration delay, Runnable work) {
        Objects.requireNonNull(delay, "delay");
        Objects.requireNonNull(work, "work");
        if (delay.isNegative()) {
            throw new IllegalArgumentException("delay must not be negative.");
        }
        ScheduledFuture<?> future = executor.schedule(work, delay.toMillis(), TimeUnit.MILLISECONDS);
        return () -> future.cancel(false);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
