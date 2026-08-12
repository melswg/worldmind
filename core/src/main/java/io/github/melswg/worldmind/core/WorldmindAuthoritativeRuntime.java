package io.github.melswg.worldmind.core;

/**
 * Minimal runtime marker for the authoritative server bootstrap.
 */
public final class WorldmindAuthoritativeRuntime {
    private final AuthoritativeInitializationPath initializationPath;

    WorldmindAuthoritativeRuntime(AuthoritativeInitializationPath initializationPath) {
        this.initializationPath = initializationPath;
    }

    public AuthoritativeInitializationPath initializationPath() {
        return initializationPath;
    }
}
