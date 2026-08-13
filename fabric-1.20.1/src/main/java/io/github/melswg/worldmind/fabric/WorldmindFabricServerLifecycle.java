package io.github.melswg.worldmind.fabric;

import io.github.melswg.worldmind.core.AuthoritativeWorldmindInitializer;
import io.github.melswg.worldmind.core.WorldmindAuthoritativeRuntime;
import io.github.melswg.worldmind.core.configuration.DisabledWorldmindIntegration;
import io.github.melswg.worldmind.core.configuration.EnabledWorldmindIntegration;
import io.github.melswg.worldmind.core.configuration.WorldmindIntegrationState;
import io.github.melswg.worldmind.core.conversation.ProviderCapabilities;
import io.github.melswg.worldmind.core.conversation.CapturedPublicChatMessage;
import io.github.melswg.worldmind.core.conversation.WorldIdentity;
import io.github.melswg.worldmind.fabric.configuration.WorldmindStartupConfigurationLoader;
import io.github.melswg.worldmind.fabric.provider.CustomOpenAiCompatibleLanguageModel;
import io.github.melswg.worldmind.fabric.provider.EnvironmentProviderCredentialResolver;
import io.github.melswg.worldmind.fabric.provider.ProviderCredentialResolver;
import io.github.melswg.worldmind.storage.sqlite.SqliteDialogueJournal;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.message.MessageLink;
import net.minecraft.network.message.SignedMessage;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.WorldSavePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fabric boundary for the authoritative logical-server lifecycle.
 */
final class WorldmindFabricServerLifecycle {
    private static final Logger LOGGER = LoggerFactory.getLogger("worldmind");

    private final AuthoritativeWorldmindInitializer authoritativeInitializer;
    private final WorldmindStartupConfigurationLoader configurationLoader;
    private final ProviderCredentialResolver providerCredentials;
    private final FabricCommandBroadcastCorrelation commandBroadcastCorrelation = new FabricCommandBroadcastCorrelation();
    private WorldmindAuthoritativeRuntime runtime;
    private FabricChatObservationRuntime chatObservation;
    private WorldIdentityLifecycle worldIdentity;
    private PendingJournalStart pendingJournalStart;
    private long lifecycleGeneration;

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
        this.authoritativeInitializer = authoritativeInitializer;
        this.configurationLoader = configurationLoader;
        this.providerCredentials = providerCredentials;
    }

    synchronized void onServerStarted(MinecraftServer server) {
        closeChatObservation();
        long generation = ++lifecycleGeneration;
        WorldmindIntegrationState integrationState = configurationLoader.load();
        runtime = authoritativeInitializer.initialize(integrationState);
        if (server != null && integrationState instanceof EnabledWorldmindIntegration enabled) {
            startChatRuntime(server, enabled, generation);
        }
        logStartupState(integrationState);
    }

    synchronized void onServerStopping(MinecraftServer ignored) {
        lifecycleGeneration++;
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
        PendingJournalStart pending;
        synchronized (this) {
            observation = chatObservation;
            identity = worldIdentity;
            pending = pendingJournalStart;
        }
        if (commandBroadcastCorrelation.consumeIfPlayerCommandBroadcast(correlationKey(message))) {
            return;
        }
        if (observation == null || identity == null) {
            if (pending != null) {
                try {
                    pending.capture(FabricChatObservationRuntime.captureAcceptedPlayerChat(
                        message, sender, pending.characterName(), java.time.Clock.systemUTC()
                    ));
                } catch (RuntimeException failure) {
                    LOGGER.warn("Worldmind could not capture an accepted public chat message while opening its journal: {}.",
                        failure.getClass().getSimpleName());
                }
            }
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

    private void startChatRuntime(MinecraftServer server, EnabledWorldmindIntegration enabled, long generation) {
        PendingJournalStart pending = new PendingJournalStart(generation, enabled.configuration().profile().characterName(),
            enabled.configuration().profile().chatNameColor());
        synchronized (this) {
            if (generation != lifecycleGeneration) {
                return;
            }
            pendingJournalStart = pending;
        }
        java.nio.file.Path database = journalDatabasePath(server.getSavePath(WorldSavePath.ROOT));
        SqliteDialogueJournal.open(database).whenComplete((journal, failure) -> {
            if (failure != null || journal == null) {
                try {
                    server.execute(() -> failPendingJournalStart(server, generation, failure));
                } catch (RuntimeException ignored) {
                    // A stopping server cannot receive the private unavailable notice.
                }
                return;
            }
            try {
                server.execute(() -> installOpenedJournal(server, enabled, generation, journal));
            } catch (RuntimeException failureToSchedule) {
                journal.closeAsync();
            }
        });
    }

    private synchronized void installOpenedJournal(
        MinecraftServer server,
        EnabledWorldmindIntegration enabled,
        long generation,
        SqliteDialogueJournal journal
    ) {
        if (generation != lifecycleGeneration || chatObservation != null) {
            journal.closeAsync();
            return;
        }
        WorldIdentityLifecycle identity = new WorldIdentityLifecycle(journal.openedWorldIdentity());
        try {
            chatObservation = FabricChatObservationRuntime.createProduction(
                server,
                identity.identity(),
                journal,
                enabled.configuration(),
                CustomOpenAiCompatibleLanguageModel.create(
                    enabled.configuration().globalConfiguration().provider(),
                    providerCredentials
                ),
                new ProviderCapabilities(true),
                this::logDeliveryDiagnostic
            );
            worldIdentity = identity;
            PendingJournalStart pending = pendingJournalStart;
            pendingJournalStart = null;
            if (pending != null && pending.generation() == generation) {
                for (CapturedPublicChatMessage captured : pending.drain()) {
                    chatObservation.observeCapturedPublicChat(captured, identity.identity());
                }
            }
        } catch (RuntimeException failure) {
            journal.closeAsync();
            failPendingJournalStart(server, generation, failure);
            LOGGER.warn("Worldmind chat runtime could not start: {}.", failure.getClass().getSimpleName());
        }
    }

    private void logDeliveryDiagnostic(FabricChatDeliveryDiagnostic diagnostic) {
        if (diagnostic.queueSnapshot().isPresent()) {
            var queue = diagnostic.queueSnapshot().orElseThrow();
            LOGGER.warn(
                "Worldmind {} work for opaque world {} at chat batch {}-{}; queued={}, active={}",
                diagnostic.workKind().orElseThrow(),
                diagnostic.opaqueWorldIdentity().orElseThrow(),
                diagnostic.firstSequence(),
                diagnostic.lastSequence(),
                queue.queued(),
                queue.inFlight()
            );
            return;
        }
        diagnostic.refusalCode().ifPresentOrElse(
            code -> LOGGER.warn(
                "Worldmind chat batch {}-{} ended with {}.",
                diagnostic.firstSequence(),
                diagnostic.lastSequence(),
                code
            ),
            () -> LOGGER.warn(
                "Worldmind {} for chat batch {}-{}.",
                diagnostic.kind(),
                diagnostic.firstSequence(),
                diagnostic.lastSequence()
            )
        );
    }

    private void closeChatObservation() {
        commandBroadcastCorrelation.clear();
        if (chatObservation != null) {
            chatObservation.close();
            chatObservation = null;
        }
        worldIdentity = null;
        pendingJournalStart = null;
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

    /** Save-relative location of the one world-owned dialogue journal. */
    static java.nio.file.Path journalDatabasePath(java.nio.file.Path saveRoot) {
        return java.util.Objects.requireNonNull(saveRoot, "saveRoot")
            .resolve("worldmind")
            .resolve(SqliteDialogueJournal.DATABASE_FILE_NAME);
    }

    /** Opaque identity persisted in this save's journal, never derived from a save path or display name. */
    private record WorldIdentityLifecycle(WorldIdentity identity) {
    }

    /** Temporary startup-only copied values, drained serially once SQLite reports the persistent world identity. */
    private static final class PendingJournalStart {
        private final long generation;
        private final String characterName;
        private final io.github.melswg.worldmind.core.configuration.ChatNameColor chatNameColor;
        private final java.util.List<CapturedPublicChatMessage> captured = new java.util.ArrayList<>();

        private PendingJournalStart(long generation, String characterName,
                                    io.github.melswg.worldmind.core.configuration.ChatNameColor chatNameColor) {
            this.generation = generation;
            this.characterName = characterName;
            this.chatNameColor = chatNameColor;
        }

        synchronized void capture(CapturedPublicChatMessage message) {
            captured.add(message);
        }

        synchronized java.util.List<CapturedPublicChatMessage> drain() {
            java.util.List<CapturedPublicChatMessage> result = java.util.List.copyOf(captured);
            captured.clear();
            return result;
        }

        long generation() { return generation; }
        String characterName() { return characterName; }
        io.github.melswg.worldmind.core.configuration.ChatNameColor chatNameColor() { return chatNameColor; }
    }

    private synchronized void failPendingJournalStart(MinecraftServer server, long generation, Throwable failure) {
        PendingJournalStart pending = pendingJournalStart;
        if (pending == null || pending.generation() != generation || generation != lifecycleGeneration) {
            return;
        }
        pendingJournalStart = null;
        LOGGER.warn("Worldmind dialogue journal could not open: {}.", failure == null ? "unknown" : failure.getClass().getSimpleName());
        FabricServerChatSink sink = new FabricServerChatSink(server);
        for (CapturedPublicChatMessage message : pending.drain()) {
            if (message.addressingSignal() == io.github.melswg.worldmind.core.conversation.AddressingSignal.EXACT) {
                try {
                    sink.sendPrivate(message.requester().playerId(), FabricWorldmindChatRenderer.unavailable(
                        pending.characterName(), pending.chatNameColor()
                    ));
                } catch (RuntimeException ignored) {
                    // A delivery failure cannot obstruct Minecraft startup.
                }
            }
        }
    }
}
