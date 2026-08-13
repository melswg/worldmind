package io.github.melswg.worldmind.core.conversation;

/** Stable, machine-readable reasons for declining a conversation request. */
public enum RefusalCode {
    PROVIDER_INCOMPATIBLE,
    PROVIDER_UNAVAILABLE,
    PROMPT_BUDGET_EXCEEDED,
    INVALID_PROVIDER_RESPONSE,
    EMPTY_RESPONSE,
    PROVIDER_CONNECTION_FAILURE,
    PROVIDER_TIMEOUT,
    PROVIDER_RATE_LIMITED,
    PROVIDER_SERVER_ERROR,
    PROVIDER_AUTHENTICATION_FAILURE,
    PROVIDER_HTTP_FAILURE,
    MALFORMED_PROVIDER_JSON,
    OVERSIZED_PROVIDER_CONTENT,
    JOURNAL_UNAVAILABLE,
    MEMORY_UNAVAILABLE,
    REQUEST_QUEUE_UNAVAILABLE,
    /** A fully validated runtime generation replaced this work before delivery. */
    RUNTIME_RELOADED
}
