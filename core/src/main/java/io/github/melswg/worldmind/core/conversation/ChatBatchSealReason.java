package io.github.melswg.worldmind.core.conversation;

/** The stable reason a non-empty public-chat batch was handed off. */
public enum ChatBatchSealReason {
    ADDRESSING_SIGNAL,
    MAXIMUM_MESSAGE_COUNT,
    MAXIMUM_WAIT,
    MAXIMUM_ESTIMATED_INPUT_SIZE,
    /** Raw observation was durable but could not be retained by the bounded batching gate. */
    BATCHING_CAPACITY_OVERFLOW
}
