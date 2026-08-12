package io.github.melswg.worldmind.core;

import io.github.melswg.worldmind.core.configuration.WorldmindIntegrationState;
import io.github.melswg.worldmind.core.configuration.ConfigurationDiagnostic;
import io.github.melswg.worldmind.core.configuration.DisabledWorldmindIntegration;
import io.github.melswg.worldmind.core.configuration.IntegrationDisableReason;
import java.util.List;
import java.util.Objects;

/**
 * Starts the Minecraft-independent authoritative Worldmind runtime.
 */
public final class AuthoritativeWorldmindInitializer {
    public WorldmindAuthoritativeRuntime initialize() {
        return initialize(new DisabledWorldmindIntegration(
            IntegrationDisableReason.INVALID_CONFIGURATION,
            List.of(new ConfigurationDiagnostic("startup", "No validated configuration was supplied."))
        ));
    }

    /**
     * Starts the logical-server runtime with the configuration state established
     * by the outer startup adapter.
     */
    public WorldmindAuthoritativeRuntime initialize(WorldmindIntegrationState integrationState) {
        return new WorldmindAuthoritativeRuntime(
            AuthoritativeInitializationPath.LOGICAL_SERVER,
            Objects.requireNonNull(integrationState, "integrationState")
        );
    }
}
