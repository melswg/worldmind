package io.github.melswg.worldmind.core.configuration;

import java.util.Objects;

/** The configuration passed from startup infrastructure to Minecraft-independent core code. */
public record ValidatedWorldmindConfiguration(
    WorldmindGlobalConfiguration globalConfiguration,
    WorldmindProfile profile
) {
    public ValidatedWorldmindConfiguration {
        Objects.requireNonNull(globalConfiguration, "globalConfiguration");
        Objects.requireNonNull(profile, "profile");
    }
}
