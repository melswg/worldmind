package io.github.melswg.worldmind.core.configuration;

import java.util.Objects;

/** Validated content of the versioned global Worldmind configuration. */
public record WorldmindGlobalConfiguration(
    int schemaVersion,
    boolean enabled,
    String activeProfile,
    ProviderConfiguration provider,
    ChatBatchingConfiguration chatBatching,
    RequestQueueConfiguration requestQueue,
    DialogueRetentionConfiguration dialogueRetention
) {
    public static final int V1_SCHEMA_VERSION = 1;
    public static final int V2_SCHEMA_VERSION = 2;
    public static final int V3_SCHEMA_VERSION = 3;
    public static final int CURRENT_SCHEMA_VERSION = V3_SCHEMA_VERSION;

    public WorldmindGlobalConfiguration {
        if (schemaVersion != V1_SCHEMA_VERSION && schemaVersion != V2_SCHEMA_VERSION && schemaVersion != V3_SCHEMA_VERSION) {
            throw new IllegalArgumentException("schemaVersion must be a supported Worldmind global schema.");
        }
        Objects.requireNonNull(activeProfile, "activeProfile");
        if (activeProfile.isBlank()) {
            throw new IllegalArgumentException("activeProfile must not be blank.");
        }
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(chatBatching, "chatBatching");
        Objects.requireNonNull(requestQueue, "requestQueue");
        Objects.requireNonNull(dialogueRetention, "dialogueRetention");
    }

    /** Compatibility constructor used by v1 fixtures and programmatic callers. */
    public WorldmindGlobalConfiguration(
        int schemaVersion, boolean enabled, String activeProfile, ProviderConfiguration provider,
        ChatBatchingConfiguration chatBatching, RequestQueueConfiguration requestQueue
    ) {
        this(schemaVersion, enabled, activeProfile, provider, chatBatching, requestQueue,
            DialogueRetentionConfiguration.legacyDefaults());
    }
}
