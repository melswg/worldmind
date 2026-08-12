package io.github.melswg.worldmind.core.conversation;

/** The provider-level result before it is translated for the server. */
public sealed interface LanguageModelResult permits ProviderRefusal, ProviderResponse {
}
