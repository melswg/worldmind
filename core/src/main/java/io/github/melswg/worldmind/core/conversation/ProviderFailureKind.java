package io.github.melswg.worldmind.core.conversation;

/** Redaction-safe terminal category returned by a provider attempt. */
public enum ProviderFailureKind {
    CONNECTION_FAILURE,
    TIMEOUT,
    HTTP_RATE_LIMITED,
    HTTP_SERVER_ERROR,
    HTTP_AUTHENTICATION,
    HTTP_NON_RETRYABLE,
    INCOMPATIBLE_MODEL_OR_PARAMETER,
    MALFORMED_JSON,
    MALFORMED_RESPONSE,
    EMPTY_CONTENT,
    OVERSIZED_CONTENT,
    CANCELLED
}
