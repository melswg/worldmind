package io.github.melswg.worldmind.fabric;

import io.github.melswg.worldmind.core.AuthoritativeWorldmindInitializer;
import io.github.melswg.worldmind.core.WorldmindAuthoritativeRuntime;
import io.github.melswg.worldmind.core.configuration.DisabledWorldmindIntegration;
import io.github.melswg.worldmind.core.configuration.WorldmindIntegrationState;
import io.github.melswg.worldmind.fabric.configuration.WorldmindStartupConfigurationLoader;
import io.github.melswg.worldmind.fabric.provider.EnvironmentProviderCredentialResolver;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fabric boundary for the authoritative logical-server lifecycle.
 */
final class WorldmindFabricServerLifecycle {
    private static final Logger LOGGER = LoggerFactory.getLogger("worldmind");

    private final AuthoritativeWorldmindInitializer authoritativeInitializer;
    private final WorldmindStartupConfigurationLoader configurationLoader;
    private WorldmindAuthoritativeRuntime runtime;

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

    void onServerStarted(MinecraftServer server) {
        WorldmindIntegrationState integrationState = configurationLoader.load();
        runtime = authoritativeInitializer.initialize(integrationState);
        logStartupState(integrationState);
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
}
