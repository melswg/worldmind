package io.github.melswg.worldmind.core.conversation;

/** Stable, machine-readable reasons for declining a conversation request. */
public enum RefusalCode {
    PROVIDER_INCOMPATIBLE,
    PROVIDER_UNAVAILABLE,
    PROMPT_BUDGET_EXCEEDED,
    INVALID_PROVIDER_RESPONSE,
    EMPTY_RESPONSE
}
