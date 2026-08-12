package io.github.melswg.worldmind.fabric;

import io.github.melswg.worldmind.core.configuration.ValidatedWorldmindConfiguration;
import io.github.melswg.worldmind.core.conversation.ChatBatchCoordinator;
import io.github.melswg.worldmind.core.conversation.ServerRequester;
import io.github.melswg.worldmind.core.conversation.UntrustedContext;
import io.github.melswg.worldmind.core.conversation.WorldIdentity;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.network.message.SignedMessage;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

/**
 * Logical-server-only Fabric adapter. It copies Minecraft values immediately
 * and retains only core contracts after each event callback returns.
 */
final class FabricChatObservationRuntime implements AutoCloseable {
    private final FabricDelayedScheduler delayedScheduler;
    private final ChatBatchCoordinator batchCoordinator;

    FabricChatObservationRuntime(ValidatedWorldmindConfiguration configuration) {
        delayedScheduler = new FabricDelayedScheduler();
        batchCoordinator = new ChatBatchCoordinator(
            configuration.globalConfiguration().chatBatching(),
            configuration.profile().characterName(),
            Clock.systemUTC(),
            delayedScheduler,
            ignored -> CompletableFuture.completedFuture(null)
        );
    }

    void observeAcceptedPlayerChat(SignedMessage message, ServerPlayerEntity sender, WorldIdentity worldIdentity) {
        String originalMessage = message.getContent().getString();
        String visiblePlayerName = sender.getDisplayName().getString();
        UUID playerId = sender.getUuid();
        List<UntrustedContext> context = List.of(normalizeVanillaContext(sender));
        batchCoordinator.observe(
            worldIdentity,
            new ServerRequester(playerId, visiblePlayerName),
            originalMessage,
            context
        );
    }

    @Override
    public void close() {
        batchCoordinator.close();
        delayedScheduler.close();
    }

    private UntrustedContext normalizeVanillaContext(ServerPlayerEntity sender) {
        ServerWorld world = sender.getServerWorld();
        String weather = world.isThundering() ? "thunder" : world.isRaining() ? "rain" : "clear";
        return new UntrustedContext(
            "vanilla-game-context",
            "dimension=" + world.getRegistryKey().getValue()
                + "; gameTime=" + world.getTime()
                + "; weather=" + weather
        );
    }
}
