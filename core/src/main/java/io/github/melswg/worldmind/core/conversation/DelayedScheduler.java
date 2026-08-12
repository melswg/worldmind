package io.github.melswg.worldmind.core.conversation;

import java.time.Duration;

/** Minecraft-independent scheduling port for finite pending-batch deadlines. */
@FunctionalInterface
public interface DelayedScheduler {
    ScheduledWork schedule(Duration delay, Runnable work);
}
