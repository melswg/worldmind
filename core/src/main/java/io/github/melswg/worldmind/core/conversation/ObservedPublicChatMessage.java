package io.github.melswg.worldmind.core.conversation;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** A normalized accepted public player message with its per-world sequence. */
public record ObservedPublicChatMessage(
    long sequence,
    ServerRequester requester,
    String message,
    AddressingSignal addressingSignal,
    Instant capturedAt,
    List<UntrustedContext> currentContext
) {
    public ObservedPublicChatMessage {
        if (sequence <= 0) {
            throw new IllegalArgumentException("sequence must be positive.");
        }
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
