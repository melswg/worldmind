package io.github.melswg.worldmind.core.journal;

/** Observable server-chat result after a journaled batch finishes. */
public enum JournalDeliveryStatus {
    PUBLIC_REPLY_DELIVERED,
    PUBLIC_REPLY_DELIVERY_FAILED,
    PRIVATE_UNAVAILABLE_DELIVERED,
    PRIVATE_UNAVAILABLE_UNDELIVERABLE,
    NO_OUTPUT,
    ROUTING_SKIPPED
}
