package io.github.melswg.worldmind.core.conversation;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Immutable Minecraft-independent values copied at the server chat boundary
 * before a per-world sequence is assigned by the batch coordinator.
 */
public record CapturedPublicChatMessage(
    ServerRequester requester,
    String message,
    AddressingSignal addressingSignal,
    Instant capturedAt,
    List<UntrustedContext> currentContext
) {
    public CapturedPublicChatMessage {
        Objects.requireNonNull(requester, "requester");
        message = requireText(message, "message");
        Objects.requireNonNull(addressingSignal, "addressingSignal");
        Objects.requireNonNull(capturedAt, "capturedAt");
        currentContext = List.copyOf(Objects.requireNonNull(currentContext, "currentContext"));
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank.");
        }
        return value;
    }
}
