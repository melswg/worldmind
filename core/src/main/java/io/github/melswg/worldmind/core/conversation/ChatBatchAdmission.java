package io.github.melswg.worldmind.core.conversation;

/** Non-blocking result of attempting to admit one accepted public chat message. */
public enum ChatBatchAdmission {
    /** Captured values were accepted by the asynchronous durable journal ingress before batching. */
    QUEUED_FOR_JOURNAL,
    ACCEPTED_PENDING,
    SEALED_FOR_HANDOFF,
    REJECTED_CAPACITY,
    IGNORED_AFTER_CLOSE
}
