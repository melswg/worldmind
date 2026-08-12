package io.github.melswg.worldmind.core;

/**
 * Starts the Minecraft-independent authoritative Worldmind runtime.
 */
public final class AuthoritativeWorldmindInitializer {
    public WorldmindAuthoritativeRuntime initialize() {
        return new WorldmindAuthoritativeRuntime(AuthoritativeInitializationPath.LOGICAL_SERVER);
    }
}
