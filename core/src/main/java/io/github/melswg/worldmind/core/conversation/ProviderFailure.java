package io.github.melswg.worldmind.core.conversation;

import java.util.Objects;

/** A provider failure deliberately stripped of response bodies, headers, and exception text. */
public record ProviderFailure(ProviderFailureKind kind) implements LanguageModelResult {
    public ProviderFailure {
        Objects.requireNonNull(kind, "kind");
    }
}
