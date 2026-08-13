package io.github.melswg.worldmind.core.memory;

import io.github.melswg.worldmind.core.conversation.ServerRequester;
import java.time.Instant;
import java.util.Objects;

/** Copied raw dialogue supplied to a compaction generator; it has no Minecraft references. */
public record CompactionSource(long sequence, ServerRequester requester, String text, Instant capturedAt) {
    public CompactionSource {
        if (sequence <= 0) throw new IllegalArgumentException("sequence must be positive.");
        Objects.requireNonNull(requester, "requester");
        Objects.requireNonNull(text, "text");
        if (text.isBlank()) throw new IllegalArgumentException("text must not be blank.");
        Objects.requireNonNull(capturedAt, "capturedAt");
    }
}
