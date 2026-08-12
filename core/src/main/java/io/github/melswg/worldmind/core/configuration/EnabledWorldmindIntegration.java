package io.github.melswg.worldmind.core.configuration;

import java.util.Objects;

/** A startup state whose configuration and external secret availability were validated. */
public record EnabledWorldmindIntegration(
    ValidatedWorldmindConfiguration configuration
) implements WorldmindIntegrationState {
    public EnabledWorldmindIntegration {
        Objects.requireNonNull(configuration, "configuration");
    }
}
