package io.github.melswg.worldmind.fabric;

/** Safe categories for server-side delivery diagnostics. */
enum FabricChatDeliveryDiagnosticKind {
    REFUSAL,
    DIRECT_DELIVERY_FAILED,
    AMBIENT_DELIVERY_FAILED,
    PRIVATE_DELIVERY_FAILED,
    PRIVATE_RECIPIENT_UNAVAILABLE,
    COMPACTION_FAILED,
    QUEUE_REJECTED
}
