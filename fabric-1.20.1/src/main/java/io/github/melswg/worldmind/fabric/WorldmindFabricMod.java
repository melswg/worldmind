package io.github.melswg.worldmind.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

/**
 * Fabric entrypoint. Worldmind starts only when a logical server starts.
 */
public final class WorldmindFabricMod implements ModInitializer {
    private final WorldmindFabricServerLifecycle serverLifecycle = new WorldmindFabricServerLifecycle();

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(serverLifecycle::onServerStarted);
    }
}
