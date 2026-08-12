package io.github.melswg.worldmind.core.conversation;

import java.util.Objects;
import java.util.Optional;

/**
 * Minimal text-safe result for the server boundary. Minecraft component
 * formatting and configurable response limits remain adapter responsibilities.
 */
public record SafeServerResponse(String text) implements ConversationOutcome {
    public SafeServerResponse {
        Objects.requireNonNull(text, "text");
        if (text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank.");
        }
    }

    public static Optional<SafeServerResponse> fromUntrustedModelText(String modelText) {
        Objects.requireNonNull(modelText, "modelText");
        String normalized = modelText.replaceAll("\\p{Cntrl}", " ").trim();
        return normalized.isEmpty() ? Optional.empty() : Optional.of(new SafeServerResponse(normalized));
    }
}
