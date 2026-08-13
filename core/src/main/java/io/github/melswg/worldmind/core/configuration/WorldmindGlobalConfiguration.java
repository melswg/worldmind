package io.github.melswg.worldmind.core.configuration;

import java.util.Objects;

/** Validated content of the versioned global Worldmind configuration. */
public record WorldmindGlobalConfiguration(
    int schemaVersion,
    boolean enabled,
    String activeProfile,
    ProviderConfiguration provider,
    ChatBatchingConfiguration chatBatching,
    RequestQueueConfiguration requestQueue
) {
    public static final int V1_SCHEMA_VERSION = 1;

    public WorldmindGlobalConfiguration {
        if (schemaVersion != V1_SCHEMA_VERSION) {
            throw new IllegalArgumentException("schemaVersion must be exactly " + V1_SCHEMA_VERSION + ".");
        }
        Objects.requireNonNull(activeProfile, "activeProfile");
        if (activeProfile.isBlank()) {
            throw new IllegalArgumentException("activeProfile must not be blank.");
        }
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(chatBatching, "chatBatching");
        Objects.requireNonNull(requestQueue, "requestQueue");
    }
}
