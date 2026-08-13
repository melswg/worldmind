package io.github.melswg.worldmind.fabric;

import io.github.melswg.worldmind.core.AuthoritativeWorldmindInitializer;
import io.github.melswg.worldmind.core.WorldmindAuthoritativeRuntime;
import io.github.melswg.worldmind.core.administration.AdministrationResultCode;
import io.github.melswg.worldmind.core.administration.ChatBatchingStatus;
import io.github.melswg.worldmind.core.administration.CompactionStatus;
import io.github.melswg.worldmind.core.administration.ConfigurationValidationReport;
import io.github.melswg.worldmind.core.administration.MemoryInspectionQuery;
import io.github.melswg.worldmind.core.administration.MemoryInspectionRepository;
import io.github.melswg.worldmind.core.administration.MemoryInspectionResult;
import io.github.melswg.worldmind.core.administration.MemoryInspectionScope;
import io.github.melswg.worldmind.core.administration.MemoryRecordType;
import io.github.melswg.worldmind.core.administration.MemoryExportRepository;
import io.github.melswg.worldmind.core.administration.MemoryExportResult;
import io.github.melswg.worldmind.core.administration.MemoryDeletionPreview;
import io.github.melswg.worldmind.core.administration.MemoryDeletionRequest;
import io.github.melswg.worldmind.core.administration.MemoryDeletionResult;
import io.github.melswg.worldmind.core.administration.MemoryDeletionKind;
import io.github.melswg.worldmind.core.administration.ConfirmationToken;
import io.github.melswg.worldmind.core.administration.ProviderAvailability;
import io.github.melswg.worldmind.core.administration.ReloadResult;
import io.github.melswg.worldmind.core.administration.RuntimeLifecycleState;
import io.github.melswg.worldmind.core.administration.RuntimeReloadState;
import io.github.melswg.worldmind.core.administration.RuntimeStatusSnapshot;
import io.github.melswg.worldmind.core.administration.StorageHealth;
import io.github.melswg.worldmind.core.administration.WorkStatus;
import io.github.melswg.worldmind.core.administration.WorldmindAdministration;
import io.github.melswg.worldmind.core.configuration.DisabledWorldmindIntegration;
import io.github.melswg.worldmind.core.configuration.EnabledWorldmindIntegration;
import io.github.melswg.worldmind.core.configuration.IntegrationDisableReason;
import io.github.melswg.worldmind.core.configuration.WorldmindIntegrationState;
import io.github.melswg.worldmind.core.conversation.CapturedPublicChatMessage;
import io.github.melswg.worldmind.core.conversation.ProviderCapabilities;
import io.github.melswg.worldmind.core.conversation.ProviderCircuitSnapshot;
import io.github.melswg.worldmind.core.conversation.WorldIdentity;
import io.github.melswg.worldmind.fabric.configuration.WorldmindStartupConfigurationLoader;
import io.github.melswg.worldmind.fabric.provider.CustomOpenAiCompatibleLanguageModel;
import io.github.melswg.worldmind.fabric.provider.EnvironmentProviderCredentialResolver;
import io.github.melswg.worldmind.fabric.provider.ProviderCredentialResolver;
import io.github.melswg.worldmind.storage.sqlite.SqliteDialogueJournal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.message.MessageLink;
import net.minecraft.network.message.SignedMessage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.WorldSavePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logical-server owner for the persistent world journal and the replaceable
 * configuration-dependent chat generation.  No filesystem, SQLite shutdown,
 * or provider lifecycle wait occurs on the Minecraft server thread.
 */
final class WorldmindFabricServerLifecycle implements WorldmindAdministration {
    private static final Logger LOGGER = LoggerFactory.getLogger("worldmind");

    private final AuthoritativeWorldmindInitializer authoritativeInitializer;
    private final WorldmindStartupConfigurationLoader configurationLoader;
    private final ProviderCredentialResolver providerCredentials;
    private final FabricCommandBroadcastCorrelation commandBroadcastCorrelation = new FabricCommandBroadcastCorrelation();
    private final ExecutorService administrationExecutor;
    private final ExecutorService exportExecutor;
    private final WorldmindMemoryExportPublisher exportPublisher;
    private final AtomicBoolean reloadInProgress = new AtomicBoolean();
    private final AtomicBoolean maintenanceInProgress = new AtomicBoolean();
    private final Clock administrationClock = Clock.systemUTC();
    private final SecureRandom confirmationEntropy = new SecureRandom();
    private final Map<String, PendingConfirmation> confirmations = new LinkedHashMap<>();

    private WorldmindAuthoritativeRuntime runtime;
    private WorldmindIntegrationState integrationState;
    private FabricChatObservationRuntime chatObservation;
    private SqliteDialogueJournal journal;
    private WorldIdentityLifecycle worldIdentity;
    private PendingJournalStart pendingJournalStart;
    private MinecraftServer server;
    private Path saveRoot;
    private long lifecycleGeneration;
    private RuntimeLifecycleState lifecycleState = RuntimeLifecycleState.STOPPED;
    private RuntimeReloadState reloadState = RuntimeReloadState.IDLE;
    private StorageHealth storageHealth = StorageHealth.CLOSED;

    WorldmindFabricServerLifecycle() {
        this(new EnvironmentProviderCredentialResolver());
    }

    private WorldmindFabricServerLifecycle(EnvironmentProviderCredentialResolver providerCredentials) {
        this(
            new AuthoritativeWorldmindInitializer(),
            new WorldmindStartupConfigurationLoader(
                FabricLoader.getInstance().getConfigDir().resolve("worldmind"),
                providerCredentials
            ),
            providerCredentials
        );
    }

    WorldmindFabricServerLifecycle(
        AuthoritativeWorldmindInitializer authoritativeInitializer,
        WorldmindStartupConfigurationLoader configurationLoader
    ) {
        this(authoritativeInitializer, configurationLoader, new EnvironmentProviderCredentialResolver());
    }

    WorldmindFabricServerLifecycle(
        AuthoritativeWorldmindInitializer authoritativeInitializer,
        WorldmindStartupConfigurationLoader configurationLoader,
        ProviderCredentialResolver providerCredentials
    ) {
        this(authoritativeInitializer, configurationLoader, providerCredentials, Executors.newSingleThreadExecutor(
            daemonThreadFactory("worldmind-administration")
        ));
    }

    WorldmindFabricServerLifecycle(
        AuthoritativeWorldmindInitializer authoritativeInitializer,
        WorldmindStartupConfigurationLoader configurationLoader,
        ProviderCredentialResolver providerCredentials,
        ExecutorService administrationExecutor
    ) {
        this.authoritativeInitializer = Objects.requireNonNull(authoritativeInitializer, "authoritativeInitializer");
        this.configurationLoader = Objects.requireNonNull(configurationLoader, "configurationLoader");
        this.providerCredentials = Objects.requireNonNull(providerCredentials, "providerCredentials");
        this.administrationExecutor = Objects.requireNonNull(administrationExecutor, "administrationExecutor");
        this.exportExecutor = Executors.newSingleThreadExecutor(daemonThreadFactory("worldmind-memory-export"));
        this.exportPublisher = new WorldmindMemoryExportPublisher(exportExecutor, Clock.systemUTC());
    }

    synchronized void onServerStarted(MinecraftServer startedServer) {
        invalidateConfirmations();
        retireCurrentGeneration(true);
        server = startedServer;
        saveRoot = startedServer == null ? null : startedServer.getSavePath(WorldSavePath.ROOT);
        long generation = ++lifecycleGeneration;
        lifecycleState = RuntimeLifecycleState.STARTING;
        reloadState = RuntimeReloadState.IDLE;
        storageHealth = startedServer == null ? StorageHealth.CLOSED : StorageHealth.OPENING;
        integrationState = disabled(IntegrationDisableReason.INVALID_CONFIGURATION, "Startup validation is pending.");
        runtime = authoritativeInitializer.initialize(integrationState);

        // Existing non-Minecraft unit seams intentionally remain synchronous.
        if (startedServer == null) {
            installConfiguration(null, generation, loadConfiguration());
            return;
        }
        openJournal(startedServer, generation);
        loadAsync().whenComplete((state, failure) -> schedule(startedServer, generation,
            () -> installConfiguration(startedServer, generation, state == null ? failedState() : state)
        ));
    }

    synchronized void onServerStopping(MinecraftServer ignored) {
        invalidateConfirmations();
        lifecycleState = RuntimeLifecycleState.STOPPING;
        reloadState = RuntimeReloadState.IDLE;
        reloadInProgress.set(false);
        ++lifecycleGeneration;
        retireCurrentGeneration(true);
        server = null;
        saveRoot = null;
        lifecycleState = RuntimeLifecycleState.STOPPED;
        storageHealth = StorageHealth.CLOSED;
    }

    void onCommandBroadcast(SignedMessage message, ServerCommandSource source) {
        if (source.getEntity() instanceof ServerPlayerEntity) {
            commandBroadcastCorrelation.recordPlayerCommandBroadcast(correlationKey(message));
        }
    }

    void onAcceptedPlayerChat(SignedMessage message, ServerPlayerEntity sender) {
        FabricChatObservationRuntime observation;
        WorldIdentityLifecycle identity;
        PendingJournalStart pending;
        synchronized (this) {
            observation = chatObservation;
            identity = worldIdentity;
            pending = pendingJournalStart;
        }
        if (commandBroadcastCorrelation.consumeIfPlayerCommandBroadcast(correlationKey(message))) return;
        if (observation == null || identity == null) {
            if (pending != null) {
                try {
                    pending.capture(FabricChatObservationRuntime.captureAcceptedPlayerChat(
                        message, sender, pending.characterName(), Clock.systemUTC()
                    ));
                } catch (RuntimeException failure) {
                    LOGGER.warn("Worldmind could not capture accepted public chat while journal opens: {}.",
                        failure.getClass().getSimpleName());
                }
            }
            return;
        }
        try {
            observation.observeAcceptedPlayerChat(message, sender, identity.identity());
        } catch (RuntimeException failure) {
            LOGGER.warn("Worldmind could not normalize accepted public chat: {}.", failure.getClass().getSimpleName());
        }
    }

    WorldmindAuthoritativeRuntime runtime() {
        synchronized (this) {
            if (runtime == null) throw new IllegalStateException("Worldmind has not started on a logical server.");
            return runtime;
        }
    }

    @Override
    public synchronized RuntimeStatusSnapshot status() {
        FabricChatObservationRuntime observation = chatObservation;
        Optional<ChatBatchingStatus> batching = observation == null ? Optional.empty() : Optional.of(observation.batchingStatus());
        Optional<WorkStatus> work = observation == null ? Optional.empty() : Optional.of(observation.workStatus());
        Optional<ProviderCircuitSnapshot> circuit = observation == null
            ? Optional.empty()
            : Optional.ofNullable(observation.circuitStatus());
        Optional<CompactionStatus> compaction = observation == null ? Optional.empty() : Optional.of(observation.compactionStatus());
        EnabledWorldmindIntegration enabled = integrationState instanceof EnabledWorldmindIntegration value ? value : null;
        Optional<IntegrationDisableReason> disableReason = integrationState instanceof DisabledWorldmindIntegration disabled
            ? Optional.of(disabled.reason()) : Optional.empty();
        return new RuntimeStatusSnapshot(
            lifecycleState,
            reloadState,
            enabled != null,
            disableReason,
            enabled == null ? Optional.empty() : Optional.of(enabled.configuration().globalConfiguration().activeProfile()),
            providerAvailability(enabled),
            batching,
            work.orElse(new WorkStatus(0, 0, lifecycleState == RuntimeLifecycleState.STOPPED, 0, 0)),
            circuit,
            storageHealth,
            compaction.orElse(new CompactionStatus(0, 0, "NONE"))
        );
    }

    @Override
    public CompletionStage<ConfigurationValidationReport> validate() {
        return loadAsync().thenApply(ConfigurationValidationReport::fromIntegrationState);
    }

    @Override
    public CompletionStage<MemoryInspectionResult> inspect(MemoryInspectionQuery query) {
        Objects.requireNonNull(query, "query");
        MemoryInspectionRepository repository;
        synchronized (this) {
            if (lifecycleState != RuntimeLifecycleState.RUNNING) {
                return CompletableFuture.completedFuture(MemoryInspectionResult.of(AdministrationResultCode.LIFECYCLE_NOT_READY));
            }
            if (maintenanceInProgress.get() || journal == null || storageHealth != StorageHealth.READY) {
                return CompletableFuture.completedFuture(MemoryInspectionResult.of(AdministrationResultCode.STORAGE_NOT_READY));
            }
            repository = journal;
        }
        return repository.inspect(query).handle((page, failure) -> failure == null && page != null
            ? MemoryInspectionResult.page(page) : MemoryInspectionResult.of(AdministrationResultCode.STORAGE_UNAVAILABLE));
    }

    @Override
    public CompletionStage<MemoryInspectionResult> detail(
        MemoryInspectionScope scope,
        MemoryRecordType recordType,
        String stableIdentity
    ) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(recordType, "recordType");
        MemoryInspectionRepository repository;
        synchronized (this) {
            if (lifecycleState != RuntimeLifecycleState.RUNNING) {
                return CompletableFuture.completedFuture(MemoryInspectionResult.of(AdministrationResultCode.LIFECYCLE_NOT_READY));
            }
            if (maintenanceInProgress.get() || journal == null || storageHealth != StorageHealth.READY) {
                return CompletableFuture.completedFuture(MemoryInspectionResult.of(AdministrationResultCode.STORAGE_NOT_READY));
            }
            repository = journal;
        }
        return repository.detail(scope, recordType, stableIdentity).handle((record, failure) -> {
            if (failure != null) return MemoryInspectionResult.of(AdministrationResultCode.STORAGE_UNAVAILABLE);
            return record.map(MemoryInspectionResult::detail).orElseGet(() -> MemoryInspectionResult.of(AdministrationResultCode.NOT_FOUND));
        });
    }

    @Override
    public CompletionStage<MemoryExportResult> export(MemoryInspectionScope scope) {
        Objects.requireNonNull(scope, "scope");
        MemoryExportRepository repository;
        Path root;
        String worldId;
        synchronized (this) {
            if (lifecycleState != RuntimeLifecycleState.RUNNING) {
                return CompletableFuture.completedFuture(MemoryExportResult.of(AdministrationResultCode.LIFECYCLE_NOT_READY));
            }
            if (maintenanceInProgress.get() || journal == null || worldIdentity == null || saveRoot == null || storageHealth != StorageHealth.READY) {
                return CompletableFuture.completedFuture(MemoryExportResult.of(AdministrationResultCode.STORAGE_NOT_READY));
            }
            repository = journal;
            root = saveRoot;
            worldId = worldIdentity.identity().stableId();
        }
        return exportPublisher.export(repository, root, worldId, scope);
    }

    @Override
    public CompletionStage<MemoryDeletionPreview> prepareDeletion(MemoryDeletionRequest request) {
        Objects.requireNonNull(request, "request");
        SqliteDialogueJournal repository;
        long generation;
        synchronized (this) {
            if (lifecycleState != RuntimeLifecycleState.RUNNING) return CompletableFuture.completedFuture(MemoryDeletionPreview.of(AdministrationResultCode.LIFECYCLE_NOT_READY));
            if (maintenanceInProgress.get()) return CompletableFuture.completedFuture(MemoryDeletionPreview.of(AdministrationResultCode.DELETION_IN_PROGRESS));
            if (journal == null || storageHealth != StorageHealth.READY) return CompletableFuture.completedFuture(MemoryDeletionPreview.of(AdministrationResultCode.STORAGE_NOT_READY));
            repository = journal;
            generation = lifecycleGeneration;
        }
        return repository.prepareDeletion(request).thenApply(preview -> {
            if (preview.code() != AdministrationResultCode.SUCCESS || preview.targetFingerprint().isEmpty()) return preview;
            synchronized (this) {
                if (generation != lifecycleGeneration || repository != journal || maintenanceInProgress.get()) {
                    return MemoryDeletionPreview.of(AdministrationResultCode.TARGET_CHANGED);
                }
                if (confirmations.size() >= 32) return MemoryDeletionPreview.of(AdministrationResultCode.DELETION_IN_PROGRESS);
                ConfirmationToken token = nextConfirmationToken();
                Instant expiresAt = administrationClock.instant().plusSeconds(60);
                confirmations.put(token.value(), new PendingConfirmation(request, generation, preview.targetFingerprint().orElseThrow(), expiresAt));
                return new MemoryDeletionPreview(AdministrationResultCode.CONFIRMATION_REQUIRED, Optional.of(token),
                    preview.affectedRecords(), Optional.of(expiresAt), Optional.empty());
            }
        }).exceptionally(ignored -> MemoryDeletionPreview.of(AdministrationResultCode.STORAGE_UNAVAILABLE));
    }

    @Override
    public CompletionStage<MemoryDeletionPreview> prepareWorldReset() {
        return prepareDeletion(MemoryDeletionRequest.worldReset());
    }

    @Override
    public CompletionStage<MemoryDeletionResult> confirmDeletion(ConfirmationToken token) {
        return confirm(token, MemoryDeletionKind.DELETE_RECORD, MemoryDeletionKind.DELETE_PLAYER);
    }

    @Override
    public CompletionStage<MemoryDeletionResult> confirmWorldReset(ConfirmationToken token) {
        return confirm(token, MemoryDeletionKind.RESET_WORLD);
    }

    private CompletionStage<MemoryDeletionResult> confirm(ConfirmationToken token, MemoryDeletionKind... acceptedKinds) {
        Objects.requireNonNull(token, "token");
        PendingConfirmation pending;
        SqliteDialogueJournal repository;
        FabricChatObservationRuntime retiring;
        long operationGeneration;
        synchronized (this) {
            pending = confirmations.get(token.value());
            if (pending == null) return CompletableFuture.completedFuture(MemoryDeletionResult.of(AdministrationResultCode.CONFIRMATION_INVALID, MemoryDeletionKind.DELETE_RECORD));
            if (pending.expiresAt().isBefore(administrationClock.instant())) {
                confirmations.remove(token.value());
                return CompletableFuture.completedFuture(MemoryDeletionResult.of(AdministrationResultCode.CONFIRMATION_EXPIRED, pending.request().kind()));
            }
            boolean accepted = java.util.Arrays.stream(acceptedKinds).anyMatch(value -> value == pending.request().kind());
            if (!accepted) return CompletableFuture.completedFuture(MemoryDeletionResult.of(AdministrationResultCode.CONFIRMATION_INVALID, pending.request().kind()));
            if (pending.generation() != lifecycleGeneration || journal == null || storageHealth != StorageHealth.READY) {
                confirmations.remove(token.value());
                return CompletableFuture.completedFuture(MemoryDeletionResult.of(AdministrationResultCode.TARGET_CHANGED, pending.request().kind()));
            }
            if (!maintenanceInProgress.compareAndSet(false, true)) {
                return CompletableFuture.completedFuture(MemoryDeletionResult.of(AdministrationResultCode.DELETION_IN_PROGRESS, pending.request().kind()));
            }
            confirmations.remove(token.value());
            exportPublisher.cancelActive();
            operationGeneration = ++lifecycleGeneration;
            retiring = chatObservation;
            chatObservation = null;
            pendingJournalStart = null;
            repository = journal;
        }
        CompletionStage<Void> barrier = retiring == null ? CompletableFuture.completedFuture(null) : retiring.retireForReload();
        return barrier.handle((ignored, failure) -> null).thenCompose(ignored -> repository.executeDeletion(pending.request(), pending.fingerprint()))
            .handle((result, failure) -> {
                MemoryDeletionResult safe = failure == null && result != null
                    ? result : MemoryDeletionResult.of(AdministrationResultCode.STORAGE_UNAVAILABLE, pending.request().kind());
                synchronized (this) {
                    maintenanceInProgress.set(false);
                    if (safe.code() == AdministrationResultCode.SUCCESS) invalidateConfirmations();
                    if (operationGeneration == lifecycleGeneration && lifecycleState == RuntimeLifecycleState.RUNNING) {
                        installChatRuntimeIfReady(server, operationGeneration);
                    }
                }
                return safe;
            });
    }

    @Override
    public CompletionStage<ReloadResult> reload() {
        MinecraftServer reloadServer;
        long invocationGeneration;
        synchronized (this) {
            reloadServer = server;
            invocationGeneration = lifecycleGeneration;
            if (reloadServer == null || lifecycleState != RuntimeLifecycleState.RUNNING || journal == null
                || storageHealth != StorageHealth.READY) {
                return CompletableFuture.completedFuture(ReloadResult.of(AdministrationResultCode.LIFECYCLE_NOT_READY));
            }
            if (!reloadInProgress.compareAndSet(false, true)) {
                return CompletableFuture.completedFuture(ReloadResult.of(AdministrationResultCode.RELOAD_IN_PROGRESS));
            }
            invalidateConfirmations();
            exportPublisher.cancelActive();
            reloadState = RuntimeReloadState.VALIDATING;
        }
        CompletableFuture<ReloadResult> result = new CompletableFuture<>();
        loadAsync().whenComplete((candidate, failure) -> schedule(reloadServer, invocationGeneration, () -> {
            if (failure != null || candidate == null) {
                completeReload(result, ReloadResult.of(AdministrationResultCode.INVALID_CANDIDATE));
                return;
            }
            installReloadCandidate(reloadServer, invocationGeneration, candidate, result);
        }, () -> completeReload(result, ReloadResult.of(AdministrationResultCode.CANCELLED))));
        return result;
    }

    /** Delivers command completions only while the server generation that accepted them remains live. */
    void deliverForCurrentGeneration(MinecraftServer target, long generation, Runnable callback) {
        schedule(target, generation, callback);
    }

    /** Reload completion belongs to the initiating server lifecycle, not the retired chat generation. */
    void deliverAdministrationResult(MinecraftServer target, Runnable callback) {
        try {
            target.execute(() -> {
                synchronized (this) {
                    if (target != server || lifecycleState != RuntimeLifecycleState.RUNNING) return;
                }
                callback.run();
            });
        } catch (RuntimeException ignored) {
            // A stopped logical server has no safe command recipient.
        }
    }

    synchronized long currentGeneration() {
        return lifecycleGeneration;
    }

    private void installConfiguration(MinecraftServer startedServer, long generation, WorldmindIntegrationState state) {
        synchronized (this) {
            if (generation != lifecycleGeneration || lifecycleState == RuntimeLifecycleState.STOPPING
                || lifecycleState == RuntimeLifecycleState.STOPPED) return;
            integrationState = state;
            runtime = authoritativeInitializer.initialize(state);
            installRetentionIfReady(state);
            lifecycleState = RuntimeLifecycleState.RUNNING;
            installChatRuntimeIfReady(startedServer, generation);
        }
        logStartupState(state);
    }

    private void openJournal(MinecraftServer startedServer, long generation) {
        Path database = journalDatabasePath(startedServer.getSavePath(WorldSavePath.ROOT));
        SqliteDialogueJournal.open(database).whenComplete((opened, failure) -> schedule(startedServer, generation, () -> {
            synchronized (this) {
                if (failure != null || opened == null) {
                    storageHealth = StorageHealth.FAILED;
                    LOGGER.warn("Worldmind dialogue journal could not open: {}.",
                        failure == null ? "unknown" : failure.getClass().getSimpleName());
                    return;
                }
                if (generation != lifecycleGeneration || lifecycleState == RuntimeLifecycleState.STOPPING
                    || lifecycleState == RuntimeLifecycleState.STOPPED || journal != null) {
                    opened.closeAsync();
                    return;
                }
                journal = opened;
                worldIdentity = new WorldIdentityLifecycle(opened.openedWorldIdentity());
                storageHealth = StorageHealth.READY;
                installRetentionIfReady(integrationState);
                installChatRuntimeIfReady(startedServer, generation);
            }
        }, () -> {
            if (opened != null) opened.closeAsync();
        }));
    }

    /** Must run while synchronized. The journal deliberately outlives chat generations. */
    private void installChatRuntimeIfReady(MinecraftServer target, long generation) {
        if (target == null || generation != lifecycleGeneration || chatObservation != null || journal == null
            || worldIdentity == null || !(integrationState instanceof EnabledWorldmindIntegration enabled)) return;
        PendingJournalStart pending = new PendingJournalStart(generation, enabled.configuration().profile().characterName(),
            enabled.configuration().profile().chatNameColor());
        pendingJournalStart = pending;
        try {
            FabricChatObservationRuntime created = FabricChatObservationRuntime.createProduction(
                target,
                worldIdentity.identity(),
                journal,
                enabled.configuration(),
                CustomOpenAiCompatibleLanguageModel.create(enabled.configuration().globalConfiguration().provider(), providerCredentials),
                new ProviderCapabilities(true),
                this::logDeliveryDiagnostic
            );
            chatObservation = created;
            pendingJournalStart = null;
            for (CapturedPublicChatMessage captured : pending.drain()) {
                created.observeCapturedPublicChat(captured, worldIdentity.identity());
            }
        } catch (RuntimeException failure) {
            pendingJournalStart = null;
            LOGGER.warn("Worldmind chat runtime could not start: {}.", failure.getClass().getSimpleName());
        }
    }

    private void installReloadCandidate(
        MinecraftServer target,
        long invocationGeneration,
        WorldmindIntegrationState candidate,
        CompletableFuture<ReloadResult> result
    ) {
        synchronized (this) {
            if (invocationGeneration != lifecycleGeneration || lifecycleState != RuntimeLifecycleState.RUNNING) {
                completeReload(result, ReloadResult.of(AdministrationResultCode.CANCELLED));
                return;
            }
            ConfigurationValidationReport report = ConfigurationValidationReport.fromIntegrationState(candidate);
            if (!report.valid()) {
                completeReload(result, new ReloadResult(AdministrationResultCode.INVALID_CANDIDATE, report.diagnostics()));
                return;
            }
            if (candidate.equals(integrationState)) {
                completeReload(result, ReloadResult.of(AdministrationResultCode.NO_CHANGE));
                return;
            }
            reloadState = RuntimeReloadState.PREPARING;
            // Both configurations reference the one schema-v1 world journal; no storage/path setting is reloadable.
            long newGeneration = ++lifecycleGeneration;
            reloadState = RuntimeReloadState.RETIRING_OLD;
            FabricChatObservationRuntime retiring = chatObservation;
            chatObservation = null;
            integrationState = candidate;
            runtime = authoritativeInitializer.initialize(candidate);
            installRetentionIfReady(candidate);
            if (candidate instanceof EnabledWorldmindIntegration enabled) {
                pendingJournalStart = new PendingJournalStart(newGeneration, enabled.configuration().profile().characterName(),
                    enabled.configuration().profile().chatNameColor());
            } else {
                pendingJournalStart = null;
            }
            CompletionStage<Void> barrier = retiring == null ? CompletableFuture.completedFuture(null) : retiring.retireForReload();
            barrier.whenComplete((ignored, failure) -> schedule(target, newGeneration, () -> {
                synchronized (this) {
                    if (newGeneration != lifecycleGeneration || lifecycleState != RuntimeLifecycleState.RUNNING) {
                        completeReload(result, ReloadResult.of(AdministrationResultCode.CANCELLED));
                        return;
                    }
                    reloadState = RuntimeReloadState.ACTIVATING_NEW;
                    installChatRuntimeIfReady(target, newGeneration);
                    reloadState = RuntimeReloadState.IDLE;
                    completeReload(result, ReloadResult.of(AdministrationResultCode.SUCCESS));
                }
            }, () -> completeReload(result, ReloadResult.of(AdministrationResultCode.CANCELLED))));
        }
    }

    private synchronized void completeReload(CompletableFuture<ReloadResult> result, ReloadResult reloadResult) {
        reloadInProgress.set(false);
        if (reloadState != RuntimeReloadState.IDLE) reloadState = RuntimeReloadState.IDLE;
        result.complete(reloadResult);
    }

    private CompletionStage<WorldmindIntegrationState> loadAsync() {
        return CompletableFuture.supplyAsync(this::loadConfiguration, administrationExecutor);
    }

    /** Applies use-gates immediately; finite expiry is paged on non-server workers. */
    private void installRetentionIfReady(WorldmindIntegrationState state) {
        if (!(state instanceof EnabledWorldmindIntegration enabled) || journal == null) return;
        var policy = enabled.configuration().globalConfiguration().dialogueRetention();
        journal.configureRetention(policy);
        if (policy.hasFiniteAge()) scheduleRetentionSweep(journal, policy, lifecycleGeneration);
    }

    private void scheduleRetentionSweep(SqliteDialogueJournal target, io.github.melswg.worldmind.core.configuration.DialogueRetentionConfiguration policy, long generation) {
        target.sweepDialogueRetention(policy, Clock.systemUTC().instant()).whenComplete((result, failure) -> {
            if (failure != null || result == null || !result.moreRemaining()) return;
            CompletableFuture.delayedExecutor(1, java.util.concurrent.TimeUnit.SECONDS, administrationExecutor).execute(() -> {
                synchronized (this) {
                    if (generation != lifecycleGeneration || target != journal || lifecycleState != RuntimeLifecycleState.RUNNING) return;
                }
                scheduleRetentionSweep(target, policy, generation);
            });
        });
    }

    private WorldmindIntegrationState loadConfiguration() {
        try {
            return configurationLoader.load();
        } catch (RuntimeException failure) {
            LOGGER.warn("Worldmind configuration loading failed: {}.", failure.getClass().getSimpleName());
            return failedState();
        }
    }

    private static DisabledWorldmindIntegration failedState() {
        return disabled(IntegrationDisableReason.INVALID_CONFIGURATION, "Configuration could not be read or validated.");
    }

    private static DisabledWorldmindIntegration disabled(IntegrationDisableReason reason, String diagnostic) {
        return new DisabledWorldmindIntegration(reason,
            List.of(new io.github.melswg.worldmind.core.configuration.ConfigurationDiagnostic("configuration", diagnostic)));
    }

    private void retireCurrentGeneration(boolean closeJournal) {
        invalidateConfirmations();
        exportPublisher.cancelActive();
        commandBroadcastCorrelation.clear();
        FabricChatObservationRuntime retiring = chatObservation;
        SqliteDialogueJournal closingJournal = journal;
        chatObservation = null;
        journal = null;
        worldIdentity = null;
        pendingJournalStart = null;
        if (retiring != null) {
            retiring.close(); // Its close owns the old journal close after audited work completes.
        } else if (closeJournal && closingJournal != null) {
            closingJournal.closeAsync();
        }
    }

    private ProviderAvailability providerAvailability(EnabledWorldmindIntegration enabled) {
        if (enabled != null && chatObservation != null) {
            ProviderCircuitSnapshot circuit = chatObservation.circuitStatus();
            if (circuit != null && circuit.state() != io.github.melswg.worldmind.core.conversation.ProviderCircuitState.CLOSED) {
                return ProviderAvailability.CIRCUIT_BLOCKED;
            }
            return ProviderAvailability.READY;
        }
        if (integrationState instanceof DisabledWorldmindIntegration disabled) {
            return switch (disabled.reason()) {
                case DISABLED_BY_OPERATOR -> ProviderAvailability.DISABLED;
                case CREDENTIAL_REJECTED -> ProviderAvailability.CREDENTIAL_REJECTED;
                case SECRET_UNAVAILABLE -> ProviderAvailability.SECRET_UNREADABLE;
                default -> ProviderAvailability.NOT_READY;
            };
        }
        return ProviderAvailability.NOT_READY;
    }

    private void logStartupState(WorldmindIntegrationState state) {
        if (state instanceof DisabledWorldmindIntegration disabled) {
            LOGGER.warn("Worldmind LLM integration is disabled: {}.", disabled.reason());
            disabled.diagnostics().forEach(diagnostic -> LOGGER.warn("Worldmind configuration: {}", diagnostic.message()));
        } else {
            LOGGER.info("Worldmind configuration validated; LLM integration is ready.");
        }
    }

    private void logDeliveryDiagnostic(FabricChatDeliveryDiagnostic diagnostic) {
        if (diagnostic.queueSnapshot().isPresent()) {
            var queue = diagnostic.queueSnapshot().orElseThrow();
            LOGGER.warn("Worldmind {} work at chat batch {}-{}; queued={}, active={}",
                diagnostic.workKind().orElseThrow(), diagnostic.firstSequence(), diagnostic.lastSequence(),
                queue.queued(), queue.inFlight());
            return;
        }
        diagnostic.refusalCode().ifPresentOrElse(
            code -> LOGGER.warn("Worldmind chat batch {}-{} ended with {}.", diagnostic.firstSequence(), diagnostic.lastSequence(), code),
            () -> LOGGER.warn("Worldmind {} for chat batch {}-{}.", diagnostic.kind(), diagnostic.firstSequence(), diagnostic.lastSequence())
        );
    }

    private void schedule(MinecraftServer target, long generation, Runnable callback) {
        schedule(target, generation, callback, () -> { });
    }

    private void schedule(MinecraftServer target, long generation, Runnable callback, Runnable rejected) {
        if (target == null) {
            rejected.run();
            return;
        }
        try {
            target.execute(() -> {
                synchronized (this) {
                    if (target != server || generation != lifecycleGeneration || lifecycleState == RuntimeLifecycleState.STOPPING
                        || lifecycleState == RuntimeLifecycleState.STOPPED) {
                        rejected.run();
                        return;
                    }
                }
                callback.run();
            });
        } catch (RuntimeException failure) {
            rejected.run();
        }
    }

    private static ThreadFactory daemonThreadFactory(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    private ConfirmationToken nextConfirmationToken() {
        byte[] entropy = new byte[16];
        confirmationEntropy.nextBytes(entropy);
        return new ConfirmationToken(Base64.getUrlEncoder().withoutPadding().encodeToString(entropy));
    }

    private void invalidateConfirmations() {
        confirmations.clear();
    }

    private FabricSignedMessageCorrelationKey correlationKey(SignedMessage message) {
        MessageLink link = message.link();
        return new FabricSignedMessageCorrelationKey(link.sender(), link.sessionId(), link.index(), message.getTimestamp(), message.getSalt());
    }

    /** Save-relative location of the one world-owned dialogue journal. */
    static Path journalDatabasePath(Path saveRoot) {
        return Objects.requireNonNull(saveRoot, "saveRoot").resolve("worldmind").resolve(SqliteDialogueJournal.DATABASE_FILE_NAME);
    }

    private record WorldIdentityLifecycle(WorldIdentity identity) { }

    private record PendingConfirmation(MemoryDeletionRequest request, long generation, String fingerprint, Instant expiresAt) { }

    /** Captures observations while a generation waits for its persistent world identity. */
    private static final class PendingJournalStart {
        private final long generation;
        private final String characterName;
        private final io.github.melswg.worldmind.core.configuration.ChatNameColor chatNameColor;
        private final List<CapturedPublicChatMessage> captured = new java.util.ArrayList<>();

        private PendingJournalStart(long generation, String characterName,
                                    io.github.melswg.worldmind.core.configuration.ChatNameColor chatNameColor) {
            this.generation = generation;
            this.characterName = characterName;
            this.chatNameColor = chatNameColor;
        }

        synchronized void capture(CapturedPublicChatMessage message) { captured.add(message); }
        synchronized List<CapturedPublicChatMessage> drain() {
            List<CapturedPublicChatMessage> result = List.copyOf(captured);
            captured.clear();
            return result;
        }
        long generation() { return generation; }
        String characterName() { return characterName; }
        io.github.melswg.worldmind.core.configuration.ChatNameColor chatNameColor() { return chatNameColor; }
    }
}
