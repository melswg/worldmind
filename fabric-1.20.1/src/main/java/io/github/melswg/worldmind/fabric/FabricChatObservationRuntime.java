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
import io.github.melswg.worldmind.core.journal.DialogueJournal;
import io.github.melswg.worldmind.core.journal.JournalBatchOutcome;
import io.github.melswg.worldmind.core.journal.JournalDeliveryReport;
import io.github.melswg.worldmind.core.journal.JournalParticipationDecision;
import io.github.melswg.worldmind.core.journal.JournaledBatch;
import io.github.melswg.worldmind.core.journal.ProviderAttemptOutcome;
import io.github.melswg.worldmind.core.memory.WorldMemoryRepository;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
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
    private final DialogueJournal journal;
    private final Executor serverScheduler;
    private final Clock clock;
    private final ConversationApplicationService applicationService;
    private final ProviderCapabilities providerCapabilities;
    private final FabricChatOutcomeRouter outcomeRouter;
    private final ChatBatchCoordinator batchCoordinator;

    static FabricChatObservationRuntime createProduction(
        MinecraftServer server,
        WorldIdentity ownedWorld,
        DialogueJournal journal,
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
                journal,
                configuration,
                Clock.systemUTC(),
                delayedScheduler,
                delayedScheduler,
                serverScheduler,
                serverScheduler,
                new ConversationApplicationService(
                    languageModel,
                    serverScheduler,
                    journal instanceof WorldMemoryRepository memoryRepository
                        ? memoryRepository
                        : WorldMemoryRepository.empty()
                ),
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
        DialogueJournal journal,
        ValidatedWorldmindConfiguration configuration,
        Clock clock,
        DelayedScheduler delayedScheduler,
        AutoCloseable delayedSchedulerCloser,
        AutoCloseable serverSchedulerCloser,
        Executor serverScheduler,
        ConversationApplicationService applicationService,
        ProviderCapabilities providerCapabilities,
        ServerChatSink chatSink,
        FabricChatDiagnostics diagnostics
    ) {
        this.ownedWorld = Objects.requireNonNull(ownedWorld, "ownedWorld");
        this.journal = Objects.requireNonNull(journal, "journal");
        ValidatedWorldmindConfiguration validated = Objects.requireNonNull(configuration, "configuration");
        this.configuration = validated;
        this.delayedSchedulerCloser = Objects.requireNonNull(delayedSchedulerCloser, "delayedSchedulerCloser");
        this.serverSchedulerCloser = Objects.requireNonNull(serverSchedulerCloser, "serverSchedulerCloser");
        this.serverScheduler = Objects.requireNonNull(serverScheduler, "serverScheduler");
        this.clock = Objects.requireNonNull(clock, "clock");
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
            this.clock,
            Objects.requireNonNull(delayedScheduler, "delayedScheduler"),
            this::decideAndDeliver
        );
    }

    void observeAcceptedPlayerChat(SignedMessage message, ServerPlayerEntity sender, WorldIdentity worldIdentity) {
        if (active.get() && ownedWorld.equals(worldIdentity)) {
            observeCapturedPublicChat(captureAcceptedPlayerChat(message, sender, configuration.profile().characterName(), clock), worldIdentity);
        }
    }

    /** Copies Fabric values synchronously so journal work never retains Minecraft objects past this callback. */
    static CapturedPublicChatMessage captureAcceptedPlayerChat(
        SignedMessage message,
        ServerPlayerEntity sender,
        String characterName,
        Clock clock
    ) {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(sender, "sender");
        String originalMessage = message.getContent().getString();
        return new CapturedPublicChatMessage(
            new ServerRequester(sender.getUuid(), sender.getDisplayName().getString()),
            originalMessage,
            new io.github.melswg.worldmind.core.conversation.CharacterNameAddressingDetector(characterName).detect(originalMessage),
            Objects.requireNonNull(clock, "clock").instant(),
            List.of(normalizeVanillaContext(sender))
        );
    }

    /** Package-visible deterministic seam after the Fabric callback has copied its values. */
    ChatBatchAdmission observeCapturedPublicChat(CapturedPublicChatMessage captured, WorldIdentity worldIdentity) {
        Objects.requireNonNull(captured, "captured");
        Objects.requireNonNull(worldIdentity, "worldIdentity");
        if (!active.get() || !ownedWorld.equals(worldIdentity)) {
            return ChatBatchAdmission.IGNORED_AFTER_CLOSE;
        }
        journal.appendObservation(captured).whenComplete((observation, failure) -> serverScheduler.execute(() -> {
            if (!active.get()) {
                return;
            }
            if (failure != null || observation == null) {
                outcomeRouter.notifyStorageUnavailable(captured);
                return;
            }
            try {
                batchCoordinator.observe(observation.toObservedPublicChatMessage(captured.currentContext()), worldIdentity);
            } catch (RuntimeException ignored) {
                outcomeRouter.notifyStorageUnavailable(captured);
            }
        }));
        return ChatBatchAdmission.QUEUED_FOR_JOURNAL;
    }

    @Override
    public void close() {
        if (!active.compareAndSet(true, false)) {
            return;
        }
        batchCoordinator.close();
        closeQuietly(delayedSchedulerCloser);
        closeQuietly(serverSchedulerCloser);
        journal.closeAsync();
    }

    private CompletionStage<?> decideAndDeliver(io.github.melswg.worldmind.core.conversation.SealedChatBatch batch) {
        if (!active.get() || !ownedWorld.equals(batch.worldIdentity())) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> completed = new CompletableFuture<>();
        journal.appendBatch(batch).whenComplete((journaledBatch, persistenceFailure) -> {
            if (persistenceFailure != null || journaledBatch == null) {
                serverScheduler.execute(() -> {
                    if (active.get()) outcomeRouter.deliver(batch, new ConversationRefusal(RefusalCode.JOURNAL_UNAVAILABLE));
                    completed.complete(null);
                });
                return;
            }
            try {
                applicationService.handle(new NormalizedServerRequest(batch, configuration, providerCapabilities))
                    .whenComplete((outcome, failure) -> serverScheduler.execute(() ->
                        finishJournaledBatch(batch, journaledBatch, outcome, failure, completed)
                    ));
            } catch (RuntimeException failure) {
                serverScheduler.execute(() -> finishJournaledBatch(batch, journaledBatch, null, failure, completed));
            }
        });
        return completed;
    }

    private void finishJournaledBatch(
        io.github.melswg.worldmind.core.conversation.SealedChatBatch batch,
        JournaledBatch journaledBatch,
        ConversationOutcome outcome,
        Throwable failure,
        CompletableFuture<Void> completed
    ) {
        if (!active.get()) {
            completed.complete(null);
            return;
        }
        ConversationOutcome resolved = failure == null && outcome != null
            ? outcome
            : new ConversationRefusal(RefusalCode.PROVIDER_UNAVAILABLE);
        JournalDeliveryReport delivery = outcomeRouter.deliver(batch, resolved);
        JournalBatchOutcome audit = journalOutcome(journaledBatch, resolved, delivery);
        journal.appendOutcome(audit).whenComplete((ignored, journalFailure) -> completed.complete(null));
    }

    private JournalBatchOutcome journalOutcome(
        JournaledBatch batch,
        ConversationOutcome outcome,
        JournalDeliveryReport delivery
    ) {
        if (outcome instanceof io.github.melswg.worldmind.core.conversation.DirectReply) {
            return new JournalBatchOutcome(batch.batchId(), ProviderAttemptOutcome.SUCCEEDED,
                java.util.Optional.of(JournalParticipationDecision.DIRECT_REPLY), java.util.Optional.empty(), delivery, clock.instant());
        }
        if (outcome instanceof io.github.melswg.worldmind.core.conversation.AmbientReply) {
            return new JournalBatchOutcome(batch.batchId(), ProviderAttemptOutcome.SUCCEEDED,
                java.util.Optional.of(JournalParticipationDecision.AMBIENT_REPLY), java.util.Optional.empty(), delivery, clock.instant());
        }
        if (outcome instanceof io.github.melswg.worldmind.core.conversation.DeliberateSilence) {
            return new JournalBatchOutcome(batch.batchId(), ProviderAttemptOutcome.SUCCEEDED,
                java.util.Optional.of(JournalParticipationDecision.SILENT), java.util.Optional.empty(), delivery, clock.instant());
        }
        ConversationRefusal refusal = (ConversationRefusal) outcome;
        ProviderAttemptOutcome providerResult = switch (refusal.code()) {
            case PROVIDER_INCOMPATIBLE, PROMPT_BUDGET_EXCEEDED, MEMORY_UNAVAILABLE -> ProviderAttemptOutcome.NOT_ATTEMPTED;
            default -> ProviderAttemptOutcome.FAILED;
        };
        return new JournalBatchOutcome(batch.batchId(), providerResult,
            java.util.Optional.empty(), java.util.Optional.of(refusal.code()), delivery, clock.instant());
    }

    private static UntrustedContext normalizeVanillaContext(ServerPlayerEntity sender) {
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
