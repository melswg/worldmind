package io.github.melswg.worldmind.core.conversation;

/** A cancellable delayed callback owned by a chat-batching runtime. */
@FunctionalInterface
public interface ScheduledWork {
    void cancel();
}
