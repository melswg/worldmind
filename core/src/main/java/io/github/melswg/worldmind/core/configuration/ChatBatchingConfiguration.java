package io.github.melswg.worldmind.core.configuration;

/**
 * Validated, provider-neutral limits for one observed public-chat batch.
 *
 * <p>The input-size limit is a deterministic character estimate, not a
 * provider token budget.</p>
 */
public record ChatBatchingConfiguration(
    int maxMessages,
    int maxWaitMillis,
    int maxEstimatedInputCharacters
) {
    public ChatBatchingConfiguration {
        requirePositive(maxMessages, "maxMessages");
        requirePositive(maxWaitMillis, "maxWaitMillis");
        requirePositive(maxEstimatedInputCharacters, "maxEstimatedInputCharacters");
    }

    private static void requirePositive(int value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be positive.");
        }
    }
}
