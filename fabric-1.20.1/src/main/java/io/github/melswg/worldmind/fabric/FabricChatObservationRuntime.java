package io.github.melswg.worldmind.fabric;

import io.github.melswg.worldmind.core.configuration.ValidatedWorldmindConfiguration;
import io.github.melswg.worldmind.core.conversation.CapturedPublicChatMessage;
import io.github.melswg.worldmind.core.conversation.ChatBatchAdmission;
import io.github.melswg.worldmind.core.conversation.ChatBatchCoordinator;
import io.github.melswg.worldmind.core.conversation.ConversationApplicationService;
import io.github.melswg.worldmind.core.conversation.ConversationOutcome;
import io.github.melswg.worldmind.core.conversation.ConversationRefusal;
import io.github.melswg.worldmind.core.conversation.DelayedScheduler;
import io.github.melswg.worldmind.core.conversation.LanguageModel;
import io.github.melswg.worldmind.core.conversation.NormalizedServerRequest;
import io.github.melswg.worldmind.core.conversation.ProviderCapabilities;
import io.github.melswg.worldmind.core.conversation.RefusalCode;
import io.github.melswg.worldmind.core.conversation.ServerRequester;
import io.github.melswg.worldmind.core.conversation.UntrustedContext;
import io.github.melswg.worldmind.core.conversation.WorldIdentity;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.network.message.SignedMessage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

/**
 * Logical-server-only Fabric adapter. It copies Minecraft values immediately,
 * then owns the Ticket 07 handoff through the one Ticket 08 application
 * service and Ticket 09 delivery router.
 */
final class FabricChatObservationRuntime implements AutoCloseable {
    private final WorldIdentity ownedWorld;
    private final ValidatedWorldmindConfiguration configuration;
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final AutoCloseable delayedSchedulerCloser;
    private final AutoCloseable serverSchedulerCloser;
    private final ConversationApplicationService applicationService;
    private final ProviderCapabilities providerCapabilities;
    private final FabricChatOutcomeRouter outcomeRouter;
    private final ChatBatchCoordinator batchCoordinator;

    static FabricChatObservationRuntime createProduction(
        MinecraftServer server,
        WorldIdentity ownedWorld,
        ValidatedWorldmindConfiguration configuration,
        LanguageModel languageModel,
        ProviderCapabilities providerCapabilities,
        FabricChatDiagnostics diagnostics
    ) {
        FabricDelayedScheduler delayedScheduler = new FabricDelayedScheduler();
        try {
            FabricServerScheduler serverScheduler = new FabricServerScheduler(server);
            return new FabricChatObservationRuntime(
                ownedWorld,
                configuration,
                Clock.systemUTC(),
                delayedScheduler,
                delayedScheduler,
                serverScheduler,
                new ConversationApplicationService(languageModel, serverScheduler),
                providerCapabilities,
                new FabricServerChatSink(server),
                diagnostics
            );
        } catch (RuntimeException failure) {
            delayedScheduler.close();
            throw failure;
        }
    }

    FabricChatObservationRuntime(
        WorldIdentity ownedWorld,
        ValidatedWorldmindConfiguration configuration,
        Clock clock,
        DelayedScheduler delayedScheduler,
        AutoCloseable delayedSchedulerCloser,
        AutoCloseable serverSchedulerCloser,
        ConversationApplicationService applicationService,
        ProviderCapabilities providerCapabilities,
        ServerChatSink chatSink,
        FabricChatDiagnostics diagnostics
    ) {
        this.ownedWorld = Objects.requireNonNull(ownedWorld, "ownedWorld");
        ValidatedWorldmindConfiguration validated = Objects.requireNonNull(configuration, "configuration");
        this.configuration = validated;
        this.delayedSchedulerCloser = Objects.requireNonNull(delayedSchedulerCloser, "delayedSchedulerCloser");
        this.serverSchedulerCloser = Objects.requireNonNull(serverSchedulerCloser, "serverSchedulerCloser");
        this.applicationService = Objects.requireNonNull(applicationService, "applicationService");
        this.providerCapabilities = Objects.requireNonNull(providerCapabilities, "providerCapabilities");
        this.outcomeRouter = new FabricChatOutcomeRouter(
            ownedWorld,
            validated.profile().characterName(),
            validated.profile().chatNameColor(),
            active::get,
            chatSink,
            diagnostics
        );
        batchCoordinator = new ChatBatchCoordinator(
            validated.globalConfiguration().chatBatching(),
            validated.profile().characterName(),
            Objects.requireNonNull(clock, "clock"),
            Objects.requireNonNull(delayedScheduler, "delayedScheduler"),
            this::decideAndDeliver
        );
    }

    void observeAcceptedPlayerChat(SignedMessage message, ServerPlayerEntity sender, WorldIdentity worldIdentity) {
        String originalMessage = message.getContent().getString();
        String visiblePlayerName = sender.getDisplayName().getString();
        UUID playerId = sender.getUuid();
        List<UntrustedContext> context = List.of(normalizeVanillaContext(sender));
        if (active.get() && ownedWorld.equals(worldIdentity)) {
            batchCoordinator.observe(worldIdentity, new ServerRequester(playerId, visiblePlayerName), originalMessage, context);
        }
    }

    /** Package-visible deterministic seam after the Fabric callback has copied its values. */
    ChatBatchAdmission observeCapturedPublicChat(CapturedPublicChatMessage captured, WorldIdentity worldIdentity) {
        Objects.requireNonNull(captured, "captured");
        Objects.requireNonNull(worldIdentity, "worldIdentity");
        if (!active.get() || !ownedWorld.equals(worldIdentity)) {
            return ChatBatchAdmission.IGNORED_AFTER_CLOSE;
        }
        return batchCoordinator.observe(captured, worldIdentity);
    }

    @Override
    public void close() {
        if (!active.compareAndSet(true, false)) {
            return;
        }
        batchCoordinator.close();
        closeQuietly(delayedSchedulerCloser);
        closeQuietly(serverSchedulerCloser);
    }

    private CompletionStage<?> decideAndDeliver(io.github.melswg.worldmind.core.conversation.SealedChatBatch batch) {
        if (!active.get() || !ownedWorld.equals(batch.worldIdentity())) {
            return CompletableFuture.completedFuture(null);
        }
        try {
            return applicationService.handle(new NormalizedServerRequest(batch, configuration, providerCapabilities))
                .handle((outcome, failure) -> {
                    if (!active.get()) {
                        return null;
                    }
                    ConversationOutcome resolved = failure == null && outcome != null
                        ? outcome
                        : new ConversationRefusal(RefusalCode.PROVIDER_UNAVAILABLE);
                    outcomeRouter.deliver(batch, resolved);
                    return null;
                });
        } catch (RuntimeException failure) {
            if (active.get()) {
                outcomeRouter.deliver(batch, new ConversationRefusal(RefusalCode.PROVIDER_UNAVAILABLE));
            }
            return CompletableFuture.completedFuture(null);
        }
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

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Closing one local resource must not retain server state or stop Minecraft.
        }
    }
}
