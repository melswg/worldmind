package io.github.melswg.worldmind.fabric;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * JDK-only copy of signed-message identity facts used briefly to correlate
 * Fabric command and chat events without retaining a Minecraft object.
 */
record FabricSignedMessageCorrelationKey(
    UUID senderId,
    UUID sessionId,
    int linkIndex,
    Instant timestamp,
    long salt
) {
    FabricSignedMessageCorrelationKey {
        Objects.requireNonNull(senderId, "senderId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(timestamp, "timestamp");
    }
}
