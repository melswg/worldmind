package io.github.melswg.worldmind.core.conversation;

import java.util.Objects;

/** Text returned by a provider before server-safe normalization. */
public record ProviderResponse(String text) implements LanguageModelResult {
    public ProviderResponse {
        Objects.requireNonNull(text, "text");
    }
}
