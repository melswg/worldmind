package io.github.melswg.worldmind.testkit;

import java.util.ArrayDeque;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executor;

/** A queue-backed executor whose scheduled work advances only when tests say so. */
public final class DeterministicScheduler implements Executor {
    private final ArrayDeque<Runnable> queuedTasks = new ArrayDeque<>();

    @Override
    public synchronized void execute(Runnable command) {
        queuedTasks.add(Objects.requireNonNull(command, "command"));
        notifyAll();
    }

    public synchronized int pendingTaskCount() {
        return queuedTasks.size();
    }

    public void runNext() {
        Runnable nextTask;
        synchronized (this) {
            nextTask = queuedTasks.poll();
        }
        if (nextTask == null) {
            throw new IllegalStateException("No scheduled task is available.");
        }
        nextTask.run();
    }

    public void runUntilIdle() {
        while (pendingTaskCount() > 0) {
            runNext();
        }
    }

    /** Waits without polling or sleeping until asynchronous code reaches the server boundary. */
    public synchronized void awaitPendingTask(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        long deadline = System.nanoTime() + timeout.toNanos();
        while (queuedTasks.isEmpty()) {
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                throw new IllegalStateException("No scheduled task became available in time.");
            }
            try {
                long millis = remainingNanos / 1_000_000L;
                int nanos = (int) (remainingNanos % 1_000_000L);
                wait(millis, nanos);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for a scheduled task.", failure);
            }
        }
    }
}
