package io.github.melswg.worldmind.fabric.provider;

import io.github.melswg.worldmind.core.administration.ProviderAvailability;
import io.github.melswg.worldmind.core.conversation.LanguageModel;
import io.github.melswg.worldmind.core.conversation.ProviderCapabilities;
import java.util.Objects;
import java.util.function.Supplier;

/** Provider-neutral runtime material selected by the closed built-in registry. */
public record ProviderRuntimeHandle(
    String presetId,
    LanguageModel languageModel,
    ProviderCapabilities capabilities,
    Supplier<ProviderAvailability> availability
) {
    public ProviderRuntimeHandle {
        Objects.requireNonNull(presetId, "presetId");
        Objects.requireNonNull(languageModel, "languageModel");
        Objects.requireNonNull(capabilities, "capabilities");
        Objects.requireNonNull(availability, "availability");
    }
}
