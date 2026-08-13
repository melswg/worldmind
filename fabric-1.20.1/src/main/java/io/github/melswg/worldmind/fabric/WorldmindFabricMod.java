package io.github.melswg.worldmind.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;

/**
 * Fabric entrypoint. Worldmind starts only when a logical server starts.
 */
public final class WorldmindFabricMod implements ModInitializer {
    private final FabricGameContextEntrypoints gameContextEntrypoints = new FabricGameContextEntrypoints();
    private final WorldmindFabricServerLifecycle serverLifecycle = new WorldmindFabricServerLifecycle();

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            WorldmindCommandRegistration.register(dispatcher, serverLifecycle)
        );
        ServerLifecycleEvents.SERVER_STARTING.register(gameContextEntrypoints::onServerStarting);
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            gameContextEntrypoints.onServerStarted(server);
            serverLifecycle.onServerStarted(server, gameContextEntrypoints.runtime());
        });
        ServerWorldEvents.LOAD.register(gameContextEntrypoints::onWorldLoad);
        ServerWorldEvents.UNLOAD.register(gameContextEntrypoints::onWorldUnload);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            serverLifecycle.onServerStopping(server);
            gameContextEntrypoints.onServerStopping(server);
        });
        // CHAT_MESSAGE is Fabric's post-acceptance public-player-chat event.
        // Deliberately do not observe GAME_MESSAGE: it carries system/game and
        // future Worldmind delivery broadcasts rather than player chat.
        ServerMessageEvents.COMMAND_MESSAGE.register((message, source, parameters) ->
            serverLifecycle.onCommandBroadcast(message, source)
        );
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, parameters) ->
            serverLifecycle.onAcceptedPlayerChat(message, sender)
        );
    }
}
