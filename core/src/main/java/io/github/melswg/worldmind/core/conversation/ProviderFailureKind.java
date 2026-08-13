package io.github.melswg.worldmind.core.conversation;

/** Redaction-safe terminal category returned by a provider attempt. */
public enum ProviderFailureKind {
    CONNECTION_FAILURE,
    TIMEOUT,
    HTTP_RATE_LIMITED,
    HTTP_SERVER_ERROR,
    HTTP_AUTHENTICATION,
    HTTP_NON_RETRYABLE,
    MALFORMED_JSON,
    EMPTY_CONTENT,
    OVERSIZED_CONTENT
}
