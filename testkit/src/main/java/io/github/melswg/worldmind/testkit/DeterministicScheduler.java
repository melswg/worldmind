package io.github.melswg.worldmind.testkit;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.Executor;

/** A queue-backed executor whose scheduled work advances only when tests say so. */
public final class DeterministicScheduler implements Executor {
    private final ArrayDeque<Runnable> queuedTasks = new ArrayDeque<>();

    @Override
    public void execute(Runnable command) {
        queuedTasks.add(Objects.requireNonNull(command, "command"));
    }

    public int pendingTaskCount() {
        return queuedTasks.size();
    }

    public void runNext() {
        Runnable nextTask = queuedTasks.poll();
        if (nextTask == null) {
            throw new IllegalStateException("No scheduled task is available.");
        }
        nextTask.run();
    }

    public void runUntilIdle() {
        while (!queuedTasks.isEmpty()) {
            runNext();
        }
    }
}
