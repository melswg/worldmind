package io.github.melswg.worldmind.core.conversation;

/** Categorises core-owned asynchronous jobs for safe accounting and diagnostics. */
public enum AsyncWorkKind {
    CONVERSATION,
    COMPACTION
}
