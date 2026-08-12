package io.github.melswg.worldmind.core.conversation;

import java.util.List;
import java.util.Objects;

/** An ordered prompt layer with explicit trust and source-attributed fragments. */
public record PromptLayer(
    PromptLayerType type,
    PromptTrust trust,
    List<PromptFragment> fragments
) {
    public PromptLayer {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(trust, "trust");
        fragments = List.copyOf(Objects.requireNonNull(fragments, "fragments"));
    }
}
