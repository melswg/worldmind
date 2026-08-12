package io.github.melswg.worldmind.core;

import io.github.melswg.worldmind.core.configuration.WorldmindIntegrationState;
import java.util.Objects;

/**
 * Minimal runtime marker for the authoritative server bootstrap.
 */
public final class WorldmindAuthoritativeRuntime {
    private final AuthoritativeInitializationPath initializationPath;
    private final WorldmindIntegrationState integrationState;

    WorldmindAuthoritativeRuntime(
        AuthoritativeInitializationPath initializationPath,
        WorldmindIntegrationState integrationState
    ) {
        this.initializationPath = Objects.requireNonNull(initializationPath, "initializationPath");
        this.integrationState = Objects.requireNonNull(integrationState, "integrationState");
    }

    public AuthoritativeInitializationPath initializationPath() {
        return initializationPath;
    }

    /** Returns the validated or diagnostically disabled integration state. */
    public WorldmindIntegrationState integrationState() {
        return integrationState;
    }
}
