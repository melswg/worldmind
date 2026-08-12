package io.github.melswg.worldmind.core.conversation;

import java.util.concurrent.CompletionStage;

/** Minecraft-independent port for a future LLM provider adapter. */
@FunctionalInterface
public interface LanguageModel {
    CompletionStage<LanguageModelResult> complete(ProviderRequest request);
}
