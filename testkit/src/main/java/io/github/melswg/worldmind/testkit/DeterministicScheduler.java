package io.github.melswg.worldmind.testkit;

import io.github.melswg.worldmind.core.conversation.DelayedScheduler;
import io.github.melswg.worldmind.core.conversation.ScheduledWork;
import java.util.ArrayDeque;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.concurrent.Executor;

/** A queue-backed executor whose scheduled work advances only when tests say so. */
public final class DeterministicScheduler implements Executor, DelayedScheduler {
    private final ArrayDeque<Runnable> queuedTasks = new ArrayDeque<>();
    private final Clock clock;
    private final PriorityQueue<DelayedTask> delayedTasks = new PriorityQueue<>(
        Comparator.comparing(DelayedTask::dueAt).thenComparingLong(DelayedTask::order)
    );
    private long nextOrder;

    public DeterministicScheduler() {
        this(Clock.systemUTC());
    }

    public DeterministicScheduler(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public synchronized void execute(Runnable command) {
        queuedTasks.add(Objects.requireNonNull(command, "command"));
        notifyAll();
    }

    public synchronized int pendingTaskCount() {
        return queuedTasks.size();
    }

    @Override
    public synchronized ScheduledWork schedule(Duration delay, Runnable work) {
        Objects.requireNonNull(delay, "delay");
        Objects.requireNonNull(work, "work");
        if (delay.isNegative()) {
            throw new IllegalArgumentException("delay must not be negative.");
        }
        DelayedTask task = new DelayedTask(clock.instant().plus(delay), nextOrder++, work);
        delayedTasks.add(task);
        return () -> cancel(task);
    }

    public synchronized int pendingDelayedTaskCount() {
        return (int) delayedTasks.stream().filter(task -> !task.cancelled()).count();
    }

    /** Runs all non-cancelled callbacks due at the current controlled-clock instant. */
    public void runDueTasks() {
        while (true) {
            Runnable due;
            synchronized (this) {
                DelayedTask next = delayedTasks.peek();
                if (next == null || next.dueAt().isAfter(clock.instant())) {
                    return;
                }
                delayedTasks.poll();
                due = next.cancelled() ? null : next.work();
            }
            if (due != null) {
                due.run();
            }
        }
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

    private synchronized void cancel(DelayedTask task) {
        task.cancel();
    }

    private static final class DelayedTask {
        private final Instant dueAt;
        private final long order;
        private final Runnable work;
        private boolean cancelled;

        private DelayedTask(Instant dueAt, long order, Runnable work) {
            this.dueAt = dueAt;
            this.order = order;
            this.work = work;
        }

        Instant dueAt() {
            return dueAt;
        }

        long order() {
            return order;
        }

        Runnable work() {
            return work;
        }

        boolean cancelled() {
            return cancelled;
        }

        void cancel() {
            cancelled = true;
        }
    }
}
