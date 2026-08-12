package io.github.melswg.worldmind.core.conversation;

import java.util.Objects;

/** A provider-declared refusal that remains typed through the core boundary. */
public record ProviderRefusal(RefusalCode code) implements LanguageModelResult {
    public ProviderRefusal {
        Objects.requireNonNull(code, "code");
    }
}
