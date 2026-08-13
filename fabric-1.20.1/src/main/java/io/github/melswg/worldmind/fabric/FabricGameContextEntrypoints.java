package io.github.melswg.worldmind.fabric;

import io.github.melswg.worldmind.api.gamecontext.v1.GameContextApi;
import io.github.melswg.worldmind.api.gamecontext.v1.WorldmindGameContextEntrypoint;
import io.github.melswg.worldmind.core.conversation.CurrentGameContextResolver;
import io.github.melswg.worldmind.gamecontext.internal.GameContextExtensionRuntime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Fabric-only bridge from custom entrypoints and world events into the internal API runtime. */
final class FabricGameContextEntrypoints {
    private static final Logger LOGGER = LoggerFactory.getLogger("worldmind");

    private GameContextExtensionRuntime runtime;

    synchronized void onServerStarting(MinecraftServer server) {
        if (runtime != null) runtime.close();
        runtime = GameContextExtensionRuntime.create();
        List<DeclaredEntrypoint> declared = FabricLoader.getInstance()
            .getEntrypointContainers(GameContextApi.ENTRYPOINT_KEY, WorldmindGameContextEntrypoint.class)
            .stream()
            .map(container -> new DeclaredEntrypoint(container.getProvider().getMetadata().getId(), container.getEntrypoint()))
            .toList();
        registerAll(runtime, declared);
    }

    synchronized void onServerStarted(MinecraftServer server) {
        if (runtime != null) runtime.onServerStart(GameContextExtensionRuntime.newServerContext());
    }

    synchronized void onWorldLoad(MinecraftServer server, ServerWorld world) {
        if (runtime != null) runtime.onWorldLoad(world.getRegistryKey().getValue().toString());
    }

    synchronized void onWorldUnload(MinecraftServer server, ServerWorld world) {
        if (runtime != null) runtime.onWorldUnload(world.getRegistryKey().getValue().toString());
    }

    synchronized void onServerStopping(MinecraftServer server) {
        if (runtime != null) {
            runtime.shutdown();
            runtime = null;
        }
    }

    synchronized GameContextExtensionRuntime runtime() {
        return runtime;
    }

    synchronized CurrentGameContextResolver resolver() {
        return runtime == null ? CurrentGameContextResolver.vanillaOnly() : runtime;
    }

    static void registerAll(GameContextExtensionRuntime runtime, List<DeclaredEntrypoint> declared) {
        Objects.requireNonNull(runtime, "runtime");
        declared.stream().sorted(Comparator.comparing(DeclaredEntrypoint::owningModId)).forEach(entrypoint -> {
            try {
                entrypoint.entrypoint().register(runtime.registrarFor(entrypoint.owningModId()));
            } catch (RuntimeException failure) {
                LOGGER.warn("Worldmind rejected game-context entrypoint from {}.", entrypoint.owningModId());
            }
        });
    }

    record DeclaredEntrypoint(String owningModId, WorldmindGameContextEntrypoint entrypoint) {
        DeclaredEntrypoint {
            Objects.requireNonNull(owningModId, "owningModId");
            Objects.requireNonNull(entrypoint, "entrypoint");
        }
    }
}
