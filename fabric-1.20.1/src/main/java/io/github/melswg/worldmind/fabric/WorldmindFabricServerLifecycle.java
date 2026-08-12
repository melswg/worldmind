package io.github.melswg.worldmind.fabric;

import io.github.melswg.worldmind.core.AuthoritativeWorldmindInitializer;
import io.github.melswg.worldmind.core.WorldmindAuthoritativeRuntime;
import net.minecraft.server.MinecraftServer;

/**
 * Fabric boundary for the authoritative logical-server lifecycle.
 */
final class WorldmindFabricServerLifecycle {
    private final AuthoritativeWorldmindInitializer authoritativeInitializer;
    private WorldmindAuthoritativeRuntime runtime;

    WorldmindFabricServerLifecycle() {
        this(new AuthoritativeWorldmindInitializer());
    }

    WorldmindFabricServerLifecycle(AuthoritativeWorldmindInitializer authoritativeInitializer) {
        this.authoritativeInitializer = authoritativeInitializer;
    }

    void onServerStarted(MinecraftServer server) {
        runtime = authoritativeInitializer.initialize();
    }

    WorldmindAuthoritativeRuntime runtime() {
        if (runtime == null) {
            throw new IllegalStateException("Worldmind has not started on a logical server.");
        }
        return runtime;
    }
}
