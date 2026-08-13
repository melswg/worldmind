package io.github.melswg.worldmind.core.conversation;

/** Typed admission result; no message body, player identity, or provider data is retained. */
public enum AsyncWorkRejection {
    CAPACITY,
    CLOSED
}
