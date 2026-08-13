package io.github.melswg.worldmind.core.configuration;

/**
 * Server-side policy for raw public-chat payloads. Zero days means no age
 * expiry; the three use flags remain independent so operators can stop new
 * prompt use before a physical sweep completes.
 */
public record DialogueRetentionConfiguration(
    boolean persistRawObservations,
    int maximumRawAgeDays,
    boolean useInRecentContext,
    boolean useInCompaction,
    boolean useInRetrieval
) {
    public static final int MAXIMUM_RAW_AGE_DAYS = 3650;

    public DialogueRetentionConfiguration {
        if (maximumRawAgeDays < 0 || maximumRawAgeDays > MAXIMUM_RAW_AGE_DAYS) {
            throw new IllegalArgumentException("maximumRawAgeDays must be between 0 and " + MAXIMUM_RAW_AGE_DAYS + ".");
        }
    }

    public static DialogueRetentionConfiguration legacyDefaults() {
        return new DialogueRetentionConfiguration(true, 0, true, true, true);
    }

    public boolean hasFiniteAge() { return maximumRawAgeDays > 0; }
}
