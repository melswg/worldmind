package io.github.melswg.worldmind.fabric;

import io.github.melswg.worldmind.core.configuration.ValidatedWorldmindConfiguration;
import io.github.melswg.worldmind.core.conversation.AsyncWorkKind;
import io.github.melswg.worldmind.core.conversation.AsyncWorkRejectedException;
import io.github.melswg.worldmind.core.conversation.AsyncWorkRejection;
import io.github.melswg.worldmind.core.conversation.AsyncWorkSubmission;
import io.github.melswg.worldmind.core.conversation.BoundedAsyncWorkCoordinator;
import io.github.melswg.worldmind.core.conversation.CapturedPublicChatMessage;
import io.github.melswg.worldmind.core.conversation.ChatBatchAdmission;
import io.github.melswg.worldmind.core.conversation.ChatBatchCoordinator;
import io.github.melswg.worldmind.core.conversation.ConversationApplicationService;
import io.github.melswg.worldmind.core.conversation.ConversationExecution;
import io.github.melswg.worldmind.core.conversation.ConversationOutcome;
import io.github.melswg.worldmind.core.conversation.ConversationRefusal;
import io.github.melswg.worldmind.core.conversation.DelayedScheduler;
import io.github.melswg.worldmind.core.conversation.LanguageModel;
import io.github.melswg.worldmind.core.conversation.NormalizedServerRequest;
import io.github.melswg.worldmind.core.conversation.ProviderCapabilities;
import io.github.melswg.worldmind.core.conversation.RefusalCode;
import io.github.melswg.worldmind.core.conversation.RetryingLanguageModel;
import io.github.melswg.worldmind.core.conversation.JitterSource;
import io.github.melswg.worldmind.core.conversation.ServerRequester;
import io.github.melswg.worldmind.core.conversation.SealedChatBatch;
import io.github.melswg.worldmind.core.conversation.UntrustedContext;
import io.github.melswg.worldmind.core.conversation.WorldIdentity;
import io.github.melswg.worldmind.core.journal.DialogueJournal;
import io.github.melswg.worldmind.core.journal.JournalBatchOutcome;
import io.github.melswg.worldmind.core.journal.JournalDeliveryReport;
import io.github.melswg.worldmind.core.journal.JournalParticipationDecision;
import io.github.melswg.worldmind.core.journal.JournaledBatch;
import io.github.melswg.worldmind.core.journal.ProviderAttemptOutcome;
import io.github.melswg.worldmind.core.memory.WorldMemoryRepository;
import io.github.melswg.worldmind.core.memory.DeterministicMemoryCompactionGenerator;
import io.github.melswg.worldmind.core.memory.MemoryCompactionRepository;
import io.github.melswg.worldmind.core.memory.MemoryCompactionService;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.Set;
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
    private final MemoryCompactionService compactionService;
    private final BoundedAsyncWorkCoordinator workCoordinator;
    private final ProviderCapabilities providerCapabilities;
    private final FabricChatDiagnostics diagnostics;
    private final FabricChatOutcomeRouter outcomeRouter;
    private final ChatBatchCoordinator batchCoordinator;
    private final Set<CompletableFuture<Void>> pendingAuditWrites = ConcurrentHashMap.newKeySet();

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
                    new RetryingLanguageModel(
                        languageModel,
                        configuration.globalConfiguration().provider().retry(),
                        delayedScheduler,
                        JitterSource.random()
                    ),
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
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.compactionService = journal instanceof MemoryCompactionRepository repository
            ? new MemoryCompactionService(repository, new DeterministicMemoryCompactionGenerator())
            : null;
        this.workCoordinator = new BoundedAsyncWorkCoordinator(validated.globalConfiguration().requestQueue());
        this.providerCapabilities = Objects.requireNonNull(providerCapabilities, "providerCapabilities");
        this.outcomeRouter = new FabricChatOutcomeRouter(
            ownedWorld,
            validated.profile().characterName(),
            validated.profile().chatNameColor(),
            active::get,
            chatSink,
            this.diagnostics
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
                io.github.melswg.worldmind.core.conversation.ObservedPublicChatMessage observed =
                    observation.toObservedPublicChatMessage(captured.currentContext());
                ChatBatchAdmission admission = batchCoordinator.observe(observed, worldIdentity);
                if (admission == ChatBatchAdmission.REJECTED_CAPACITY) {
                    recordBatchingOverflow(observed);
                }
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
        CompletionStage<Void> workStopped = workCoordinator.closeAsync();
        closeQuietly(delayedSchedulerCloser);
        closeQuietly(serverSchedulerCloser);
        workStopped.thenCompose(ignored -> awaitAuditWrites()).whenComplete((ignored, failure) -> journal.closeAsync());
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
            ConversationWorkState workState = new ConversationWorkState();
            AsyncWorkSubmission<Void> submission;
            try {
                submission = workCoordinator.submit(
                    ownedWorld,
                    journaledBatch.firstSequence(),
                    AsyncWorkKind.CONVERSATION,
                    () -> processJournaledBatch(batch, journaledBatch, workState)
                );
            } catch (RuntimeException failure) {
                recordQueueRejection(batch, journaledBatch, AsyncWorkRejection.CLOSED).whenComplete((ignored, auditFailure) -> completed.complete(null));
                return;
            }
            if (!submission.accepted()) {
                recordQueueRejection(batch, journaledBatch, submission.rejection().orElseThrow())
                    .whenComplete((ignored, auditFailure) -> completed.complete(null));
                return;
            }
            submission.completion().whenComplete((ignored, failure) -> {
                if (failure instanceof AsyncWorkRejectedException rejected) {
                    recordQueueRejection(batch, journaledBatch, rejected.rejection())
                        .whenComplete((ignoredAudit, auditFailure) -> completed.complete(null));
                } else if (failure != null && workState.claimTerminalAudit()) {
                    recordCancelledActiveWork(batch, journaledBatch, workState)
                        .whenComplete((ignoredAudit, auditFailure) -> completed.complete(null));
                } else {
                    completed.complete(null);
                }
            });
        });
        return completed;
    }

    private CompletionStage<Void> processJournaledBatch(
        io.github.melswg.worldmind.core.conversation.SealedChatBatch batch,
        JournaledBatch journaledBatch,
        ConversationWorkState workState
    ) {
        CompletionStage<ConversationExecution> outcomes;
        try {
            outcomes = applicationService.handleTracked(new NormalizedServerRequest(batch, configuration, providerCapabilities));
            if (outcomes == null) outcomes = CompletableFuture.failedFuture(new IllegalStateException("Conversation service returned no stage."));
        } catch (RuntimeException failure) {
            outcomes = CompletableFuture.failedFuture(failure);
        }
        return outcomes.handle((execution, failure) -> {
            ConversationExecution resolved = failure == null && execution != null
                ? execution
                : new ConversationExecution(new ConversationRefusal(RefusalCode.PROVIDER_UNAVAILABLE), workState.providerAttempted.get());
            workState.providerAttempted.set(resolved.providerAttempted());
            return resolved;
        }).thenCompose(resolved -> recordResolvedOutcome(batch, journaledBatch, resolved.outcome(), workState));
    }

    private CompletionStage<Void> recordResolvedOutcome(
        io.github.melswg.worldmind.core.conversation.SealedChatBatch batch,
        JournaledBatch journaledBatch,
        ConversationOutcome resolved,
        ConversationWorkState workState
    ) {
        CompletableFuture<Void> completed = new CompletableFuture<>();
        Runnable deliverAndPersist = () -> {
            if (!workState.claimTerminalAudit()) {
                completed.complete(null);
                return;
            }
            JournalDeliveryReport delivery = outcomeRouter.deliver(batch, resolved);
            JournalBatchOutcome audit = journalOutcome(journaledBatch, resolved, delivery);
            appendTrackedOutcome(audit).whenComplete((ignored, journalFailure) -> {
                if (journalFailure == null) startCompaction(journaledBatch);
                completed.complete(null);
            });
        };
        if (active.get()) serverScheduler.execute(deliverAndPersist); else deliverAndPersist.run();
        return completed;
    }

    private CompletionStage<Void> recordQueueRejection(
        SealedChatBatch batch,
        JournaledBatch journaledBatch,
        AsyncWorkRejection rejection
    ) {
        CompletableFuture<Void> completed = new CompletableFuture<>();
        Runnable deliverAndPersist = () -> {
            JournalDeliveryReport delivery = outcomeRouter.deliver(batch, new ConversationRefusal(RefusalCode.REQUEST_QUEUE_UNAVAILABLE));
            JournalBatchOutcome audit = new JournalBatchOutcome(
                journaledBatch.batchId(), ProviderAttemptOutcome.NOT_ATTEMPTED, java.util.Optional.empty(),
                java.util.Optional.of(RefusalCode.REQUEST_QUEUE_UNAVAILABLE), delivery, clock.instant()
            );
            appendTrackedOutcome(audit).whenComplete((ignored, failure) -> completed.complete(null));
        };
        diagnostics.record(FabricChatDeliveryDiagnostic.queueRejection(
            AsyncWorkKind.CONVERSATION,
            ownedWorld,
            journaledBatch.firstSequence(),
            journaledBatch.lastSequence(),
            workCoordinator.snapshot()
        ));
        if (active.get()) serverScheduler.execute(deliverAndPersist); else deliverAndPersist.run();
        return completed;
    }

    private CompletionStage<Void> recordCancelledActiveWork(
        SealedChatBatch batch,
        JournaledBatch journaledBatch,
        ConversationWorkState workState
    ) {
        JournalDeliveryReport delivery = outcomeRouter.deliver(batch, new ConversationRefusal(RefusalCode.REQUEST_QUEUE_UNAVAILABLE));
        JournalBatchOutcome audit = new JournalBatchOutcome(
            journaledBatch.batchId(),
            workState.providerAttempted.get() ? ProviderAttemptOutcome.FAILED : ProviderAttemptOutcome.NOT_ATTEMPTED,
            java.util.Optional.empty(),
            java.util.Optional.of(RefusalCode.REQUEST_QUEUE_UNAVAILABLE),
            delivery,
            clock.instant()
        );
        return appendTrackedOutcome(audit);
    }

    private void recordBatchingOverflow(io.github.melswg.worldmind.core.conversation.ObservedPublicChatMessage observed) {
        SealedChatBatch overflow = new SealedChatBatch(
            ownedWorld,
            List.of(observed),
            io.github.melswg.worldmind.core.conversation.ChatBatchSealReason.BATCHING_CAPACITY_OVERFLOW,
            observed.currentContext()
        );
        journal.appendBatch(overflow).whenComplete((journaledBatch, failure) -> {
            if (failure == null && journaledBatch != null) {
                recordQueueRejection(overflow, journaledBatch, AsyncWorkRejection.CAPACITY);
            } else if (active.get() && observed.addressingSignal() == io.github.melswg.worldmind.core.conversation.AddressingSignal.EXACT) {
                outcomeRouter.deliver(overflow, new ConversationRefusal(RefusalCode.JOURNAL_UNAVAILABLE));
            }
        });
    }

    private CompletionStage<Void> appendTrackedOutcome(JournalBatchOutcome audit) {
        CompletableFuture<Void> completed = new CompletableFuture<>();
        pendingAuditWrites.add(completed);
        completed.whenComplete((ignored, failure) -> pendingAuditWrites.remove(completed));
        journal.appendOutcome(audit).whenComplete((ignored, failure) -> completed.complete(null));
        return completed;
    }

    @SuppressWarnings("unchecked")
    private CompletionStage<Void> awaitAuditWrites() {
        CompletableFuture<Void>[] snapshot = pendingAuditWrites.toArray(new CompletableFuture[0]);
        return snapshot.length == 0 ? CompletableFuture.completedFuture(null) : CompletableFuture.allOf(snapshot);
    }

    private void startCompaction(JournaledBatch journaledBatch) {
        if (compactionService == null || !active.get()) return;
        AsyncWorkSubmission<Void> submission = workCoordinator.submit(
            ownedWorld, journaledBatch.lastSequence(), AsyncWorkKind.COMPACTION, () -> compactionService.compactNext(ownedWorld)
        );
        if (!submission.accepted()) return;
        submission.completion().whenComplete((ignored, failure) -> {
            if (failure != null && active.get()) {
                diagnostics.record(FabricChatDeliveryDiagnostic.delivery(
                    FabricChatDeliveryDiagnosticKind.COMPACTION_FAILED, journaledBatch.firstSequence(), journaledBatch.lastSequence()
                ));
            }
        });
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
            case PROVIDER_INCOMPATIBLE, PROMPT_BUDGET_EXCEEDED, MEMORY_UNAVAILABLE, REQUEST_QUEUE_UNAVAILABLE -> ProviderAttemptOutcome.NOT_ATTEMPTED;
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

    private static final class ConversationWorkState {
        private final AtomicBoolean providerAttempted = new AtomicBoolean();
        private final AtomicBoolean terminalAuditClaimed = new AtomicBoolean();

        private boolean claimTerminalAudit() {
            return terminalAuditClaimed.compareAndSet(false, true);
        }
    }
}
