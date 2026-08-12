package io.github.melswg.worldmind.fabric;

import io.github.melswg.worldmind.core.AuthoritativeWorldmindInitializer;
import io.github.melswg.worldmind.core.WorldmindAuthoritativeRuntime;
import io.github.melswg.worldmind.core.configuration.DisabledWorldmindIntegration;
import io.github.melswg.worldmind.core.configuration.EnabledWorldmindIntegration;
import io.github.melswg.worldmind.core.configuration.WorldmindIntegrationState;
import io.github.melswg.worldmind.core.conversation.WorldIdentity;
import io.github.melswg.worldmind.fabric.configuration.WorldmindStartupConfigurationLoader;
import io.github.melswg.worldmind.fabric.provider.EnvironmentProviderCredentialResolver;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.message.MessageLink;
import net.minecraft.network.message.SignedMessage;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fabric boundary for the authoritative logical-server lifecycle.
 */
final class WorldmindFabricServerLifecycle {
    private static final Logger LOGGER = LoggerFactory.getLogger("worldmind");

    private final AuthoritativeWorldmindInitializer authoritativeInitializer;
    private final WorldmindStartupConfigurationLoader configurationLoader;
    private final FabricCommandBroadcastCorrelation commandBroadcastCorrelation = new FabricCommandBroadcastCorrelation();
    private WorldmindAuthoritativeRuntime runtime;
    private FabricChatObservationRuntime chatObservation;
    private WorldIdentityLifecycle worldIdentity;

    WorldmindFabricServerLifecycle() {
        this(
            new AuthoritativeWorldmindInitializer(),
            new WorldmindStartupConfigurationLoader(
                FabricLoader.getInstance().getConfigDir().resolve("worldmind"),
                new EnvironmentProviderCredentialResolver()
            )
        );
    }

    WorldmindFabricServerLifecycle(
        AuthoritativeWorldmindInitializer authoritativeInitializer,
        WorldmindStartupConfigurationLoader configurationLoader
    ) {
        this.authoritativeInitializer = authoritativeInitializer;
        this.configurationLoader = configurationLoader;
    }

    synchronized void onServerStarted(MinecraftServer server) {
        closeChatObservation();
        WorldmindIntegrationState integrationState = configurationLoader.load();
        runtime = authoritativeInitializer.initialize(integrationState);
        if (server != null && integrationState instanceof EnabledWorldmindIntegration enabled) {
            worldIdentity = new WorldIdentityLifecycle();
            chatObservation = new FabricChatObservationRuntime(enabled.configuration());
        }
        logStartupState(integrationState);
    }

    synchronized void onServerStopping(MinecraftServer ignored) {
        closeChatObservation();
    }

    void onCommandBroadcast(SignedMessage message, ServerCommandSource source) {
        if (source.getEntity() instanceof ServerPlayerEntity) {
            commandBroadcastCorrelation.recordPlayerCommandBroadcast(correlationKey(message));
        }
    }

    void onAcceptedPlayerChat(SignedMessage message, ServerPlayerEntity sender) {
        FabricChatObservationRuntime observation;
        WorldIdentityLifecycle identity;
        synchronized (this) {
            observation = chatObservation;
            identity = worldIdentity;
        }
        if (observation == null || identity == null
            || commandBroadcastCorrelation.consumeIfPlayerCommandBroadcast(correlationKey(message))) {
            return;
        }
        try {
            observation.observeAcceptedPlayerChat(message, sender, identity.identity());
        } catch (RuntimeException failure) {
            LOGGER.warn("Worldmind could not normalize an accepted public chat message: {}.", failure.getClass().getSimpleName());
        }
    }

    WorldmindAuthoritativeRuntime runtime() {
        if (runtime == null) {
            throw new IllegalStateException("Worldmind has not started on a logical server.");
        }
        return runtime;
    }

    private void logStartupState(WorldmindIntegrationState integrationState) {
        if (integrationState instanceof DisabledWorldmindIntegration disabled) {
            LOGGER.warn("Worldmind LLM integration is disabled: {}.", disabled.reason());
            disabled.diagnostics().forEach(diagnostic -> LOGGER.warn("Worldmind configuration: {}", diagnostic.message()));
        } else {
            LOGGER.info("Worldmind configuration validated; LLM integration is ready.");
        }
    }

    private void closeChatObservation() {
        commandBroadcastCorrelation.clear();
        if (chatObservation != null) {
            chatObservation.close();
            chatObservation = null;
        }
        worldIdentity = null;
    }

    private FabricSignedMessageCorrelationKey correlationKey(SignedMessage message) {
        MessageLink link = message.link();
        return new FabricSignedMessageCorrelationKey(
            link.sender(),
            link.sessionId(),
            link.index(),
            message.getTimestamp(),
            message.getSalt()
        );
    }

    /** Opaque per-active-server identity; it deliberately contains no save path or display name. */
    private record WorldIdentityLifecycle(WorldIdentity identity) {
        private WorldIdentityLifecycle() {
            this(new WorldIdentity("server-" + java.util.UUID.randomUUID()));
        }
    }
}
