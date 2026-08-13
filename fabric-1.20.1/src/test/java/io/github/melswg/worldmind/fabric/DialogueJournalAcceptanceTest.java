package io.github.melswg.worldmind.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melswg.worldmind.core.configuration.ChatBatchingConfiguration;
import io.github.melswg.worldmind.core.configuration.ExternalSecretReference;
import io.github.melswg.worldmind.core.configuration.GenerationParameters;
import io.github.melswg.worldmind.core.configuration.LoreMaterial;
import io.github.melswg.worldmind.core.configuration.ProviderConfiguration;
import io.github.melswg.worldmind.core.configuration.ProviderEndpoint;
import io.github.melswg.worldmind.core.configuration.ResponseLengthLimit;
import io.github.melswg.worldmind.core.configuration.SecretAvailability;
import io.github.melswg.worldmind.core.configuration.ValidatedWorldmindConfiguration;
import io.github.melswg.worldmind.core.configuration.WorldmindGlobalConfiguration;
import io.github.melswg.worldmind.core.configuration.WorldmindProfile;
import io.github.melswg.worldmind.core.conversation.AddressingSignal;
import io.github.melswg.worldmind.core.conversation.CapturedPublicChatMessage;
import io.github.melswg.worldmind.core.conversation.ProviderCapabilities;
import io.github.melswg.worldmind.core.conversation.ServerRequester;
import io.github.melswg.worldmind.core.journal.JournalDeliveryStatus;
import io.github.melswg.worldmind.core.journal.JournalParticipationDecision;
import io.github.melswg.worldmind.core.memory.JournalSequenceRange;
import io.github.melswg.worldmind.core.memory.MemoryConfirmationAuthority;
import io.github.melswg.worldmind.core.memory.MemoryConfirmationRequest;
import io.github.melswg.worldmind.core.memory.MemoryConfidence;
import io.github.melswg.worldmind.core.memory.MemoryImportance;
import io.github.melswg.worldmind.core.memory.MemoryScope;
import io.github.melswg.worldmind.core.memory.MemoryVisibility;
import io.github.melswg.worldmind.core.memory.ProposedFactCandidate;
import io.github.melswg.worldmind.core.memory.ProposedRelationshipCandidate;
import io.github.melswg.worldmind.fabric.provider.CustomOpenAiCompatibleLanguageModel;
import io.github.melswg.worldmind.fabric.provider.ProviderCredentialResolver;
import io.github.melswg.worldmind.storage.sqlite.SqliteDialogueJournal;
import io.github.melswg.worldmind.testkit.FakeOpenAiCompatibleHttpServer;
import io.github.melswg.worldmind.testkit.WorldmindAcceptanceScenario;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.text.Text;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DialogueJournalAcceptanceTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final ServerRequester MIRA = new ServerRequester(
        UUID.fromString("6eac0fe2-1ce7-47fc-9fb7-cd98778b467a"), "Mira"
    );

    @TempDir Path temporaryDirectory;

    @Test
    void journalsFakeHttpReplyAndSilentBatchesThroughTheFabricRuntimeAndSurvivesRestart() throws Exception {
        Path database = temporaryDirectory.resolve("worldmind/worldmind.sqlite3");
        try (FakeOpenAiCompatibleHttpServer http = new FakeOpenAiCompatibleHttpServer()) {
            ProviderConfiguration provider = provider(http);
            WorldmindAcceptanceScenario scenario = scenario(provider);
            SqliteDialogueJournal journal = join(SqliteDialogueJournal.open(database));
            RecordingSink sink = new RecordingSink();
            FabricChatObservationRuntime runtime = runtime(scenario, journal, provider, sink);

            http.respondWith(200, response("DIRECT_REPLY\nThe cave is east."));
            runtime.observeCapturedPublicChat(message("Aster!", AddressingSignal.EXACT), journal.openedWorldIdentity());
            scenario.serverScheduler().awaitPendingTask(TIMEOUT);
            scenario.serverScheduler().runUntilIdle();
            http.awaitNextRequest(TIMEOUT);
            scenario.serverScheduler().awaitPendingTask(TIMEOUT);
            scenario.serverScheduler().runUntilIdle();
            assertEquals(List.of("<Aster> The cave is east."), sink.broadcasts.stream().map(Text::getString).toList());
            assertEquals(1, join(journal.readSnapshot()).outcomes().size());

            http.respondWith(200, response("SILENT"));
            runtime.observeCapturedPublicChat(message("The rain is loud.", AddressingSignal.NONE), journal.openedWorldIdentity());
            scenario.serverScheduler().awaitPendingTask(TIMEOUT);
            scenario.serverScheduler().runUntilIdle();
            http.awaitNextRequest(TIMEOUT);
            scenario.serverScheduler().awaitPendingTask(TIMEOUT);
            scenario.serverScheduler().runUntilIdle();
            var beforeRestart = join(journal.readSnapshot());
            assertEquals(List.of("Aster!", "The rain is loud."), beforeRestart.observations().stream().map(row -> row.text()).toList());
            assertEquals(
                List.of(JournalParticipationDecision.DIRECT_REPLY, JournalParticipationDecision.SILENT),
                beforeRestart.batches().stream()
                    .map(batch -> beforeRestart.outcomes().get(batch.batchId()).decision().orElseThrow())
                    .toList()
            );
            assertEquals(JournalDeliveryStatus.PUBLIC_REPLY_DELIVERED,
                beforeRestart.outcomes().get(beforeRestart.batches().get(0).batchId()).delivery().status());
            assertEquals("The cave is east.",
                beforeRestart.outcomes().get(beforeRestart.batches().get(0).batchId()).delivery().deliveredResponse().orElseThrow());
            join(journal.closeAsync());

            SqliteDialogueJournal reopened = join(SqliteDialogueJournal.open(database));
            var afterRestart = join(reopened.readSnapshot());
            assertEquals(beforeRestart.worldIdentity(), afterRestart.worldIdentity());
            assertEquals(beforeRestart.observations(), afterRestart.observations());
            assertEquals(beforeRestart.batches(), afterRestart.batches());
            assertEquals(beforeRestart.outcomes(), afterRestart.outcomes());
            join(reopened.closeAsync());
            runtime.close();
        }
    }

    @Test
    void recallsOnlyConfirmedPublicWorldAndSameUuidMemoryInALaterFakeHttpBatch() throws Exception {
        Path database = temporaryDirectory.resolve("memory/worldmind/worldmind.sqlite3");
        try (FakeOpenAiCompatibleHttpServer http = new FakeOpenAiCompatibleHttpServer()) {
            ProviderConfiguration provider = provider(http);
            SqliteDialogueJournal journal = join(SqliteDialogueJournal.open(database));
            WorldmindAcceptanceScenario scenario = scenario(provider, journal);
            RecordingSink sink = new RecordingSink();
            FabricChatObservationRuntime runtime = runtime(scenario, journal, provider, sink);

            http.respondWith(200, response("DIRECT_REPLY\nThe source batch is sealed."));
            runtime.observeCapturedPublicChat(message(MIRA, "Aster!", AddressingSignal.EXACT), journal.openedWorldIdentity());
            awaitProviderAfterMemoryRecall(scenario, http);
            scenario.serverScheduler().awaitPendingTask(TIMEOUT);
            scenario.serverScheduler().runUntilIdle();

            var sourceBatch = join(journal.readSnapshot()).batches().get(0);
            List<io.github.melswg.worldmind.core.memory.MemoryRecord> records = join(journal.appendProposed(sourceBatch, List.of(
                fact(MemoryScope.world(), MemoryVisibility.PUBLIC, "The observatory is east."),
                fact(MemoryScope.player(MIRA.playerId()), MemoryVisibility.PUBLIC, "Mira knows the east trail."),
                relationship(MemoryScope.player(MIRA.playerId()), MemoryVisibility.PUBLIC, MIRA.playerId(), "Mira trusts Aster."),
                fact(MemoryScope.player(MIRA.playerId()), MemoryVisibility.PRIVATE, "private note"),
                relationship(MemoryScope.player(MIRA.playerId()), MemoryVisibility.PRIVATE, MIRA.playerId(), "private relationship"),
                fact(MemoryScope.player(UUID.fromString("2d186ebe-224e-483c-af64-e75ccae282c9")), MemoryVisibility.PUBLIC, "other player note"),
                fact(MemoryScope.world(), MemoryVisibility.PUBLIC, "still proposed")
            )));
            records.subList(0, 6).forEach(record -> join(journal.confirm(record.id(), new MemoryConfirmationRequest(
                MemoryConfirmationAuthority.DETERMINISTIC_POLICY, "acceptance-policy"
            ))));

            http.respondWith(200, response("DIRECT_REPLY\nMemory was available."));
            ServerRequester renamedMira = new ServerRequester(MIRA.playerId(), "MiraTheScout");
            runtime.observeCapturedPublicChat(message(renamedMira, "Aster!", AddressingSignal.EXACT), journal.openedWorldIdentity());
            FakeOpenAiCompatibleHttpServer.CapturedRequest request = awaitProviderAfterMemoryRecall(scenario, http);
            assertTrue(request.body().contains("The observatory is east."));
            assertTrue(request.body().contains("Mira knows the east trail."));
            assertTrue(request.body().contains("Mira trusts Aster."));
            assertFalse(request.body().contains("private note"));
            assertFalse(request.body().contains("private relationship"));
            assertFalse(request.body().contains("other player note"));
            assertFalse(request.body().contains("still proposed"));
            scenario.serverScheduler().awaitPendingTask(TIMEOUT);
            scenario.serverScheduler().runUntilIdle();

            assertEquals(List.of("<Aster> The source batch is sealed.", "<Aster> Memory was available."),
                sink.broadcasts.stream().map(Text::getString).toList());
            assertEquals(2, join(journal.readSnapshot()).outcomes().size());
            assertEquals(7, join(journal.readMemorySnapshot()).records().size());
            var originalWorld = journal.openedWorldIdentity();
            join(journal.closeAsync());
            runtime.close();

            SqliteDialogueJournal reopened = join(SqliteDialogueJournal.open(database));
            assertEquals(originalWorld, reopened.openedWorldIdentity());
            assertEquals(7, join(reopened.readMemorySnapshot()).records().size());
            join(reopened.closeAsync());
        }
    }

    private static FabricChatObservationRuntime runtime(
        WorldmindAcceptanceScenario scenario,
        SqliteDialogueJournal journal,
        ProviderConfiguration provider,
        RecordingSink sink
    ) {
        return new FabricChatObservationRuntime(
            journal.openedWorldIdentity(), journal, configuration(provider), scenario.clock(), scenario.serverScheduler(),
            () -> { }, () -> { }, scenario.serverScheduler(), scenario.applicationService(),
            new ProviderCapabilities(true), sink, diagnostic -> { }
        );
    }

    private static WorldmindAcceptanceScenario scenario(ProviderConfiguration provider) {
        return new WorldmindAcceptanceScenario(new CustomOpenAiCompatibleLanguageModel(
            provider, HttpClient.newHttpClient(), new ProviderCredentialResolver() {
                @Override public SecretAvailability check(ExternalSecretReference reference) { return SecretAvailability.AVAILABLE; }
                @Override public Optional<String> resolveForOutgoingRequest(ExternalSecretReference reference) {
                    return Optional.of("fake-http-credential");
                }
            }
        ));
    }

    private static WorldmindAcceptanceScenario scenario(ProviderConfiguration provider, SqliteDialogueJournal memory) {
        return new WorldmindAcceptanceScenario(new CustomOpenAiCompatibleLanguageModel(
            provider, HttpClient.newHttpClient(), new ProviderCredentialResolver() {
                @Override public SecretAvailability check(ExternalSecretReference reference) { return SecretAvailability.AVAILABLE; }
                @Override public Optional<String> resolveForOutgoingRequest(ExternalSecretReference reference) {
                    return Optional.of("fake-http-credential");
                }
            }
        ), memory);
    }

    private static CapturedPublicChatMessage message(String text, AddressingSignal signal) {
        return message(MIRA, text, signal);
    }

    private static CapturedPublicChatMessage message(ServerRequester requester, String text, AddressingSignal signal) {
        return new CapturedPublicChatMessage(requester, text, signal, Instant.EPOCH, List.of());
    }

    private static ProposedFactCandidate fact(MemoryScope scope, MemoryVisibility visibility, String content) {
        return new ProposedFactCandidate(scope, visibility, new JournalSequenceRange(1, 1),
            new MemoryConfidence(0.8), new MemoryImportance(0.6), content);
    }

    private static ProposedRelationshipCandidate relationship(
        MemoryScope scope,
        MemoryVisibility visibility,
        UUID subject,
        String state
    ) {
        return new ProposedRelationshipCandidate(scope, visibility, new JournalSequenceRange(1, 1),
            new MemoryConfidence(0.8), new MemoryImportance(0.6), subject, state);
    }

    private static FakeOpenAiCompatibleHttpServer.CapturedRequest awaitProviderAfterMemoryRecall(
        WorldmindAcceptanceScenario scenario,
        FakeOpenAiCompatibleHttpServer http
    ) {
        scenario.serverScheduler().awaitPendingTask(TIMEOUT);
        scenario.serverScheduler().runUntilIdle();
        scenario.serverScheduler().awaitPendingTask(TIMEOUT);
        scenario.serverScheduler().runUntilIdle();
        return http.awaitNextRequest(TIMEOUT);
    }

    private static ProviderConfiguration provider(FakeOpenAiCompatibleHttpServer http) {
        return new ProviderConfiguration(
            CustomOpenAiCompatibleLanguageModel.PROVIDER_ID,
            new ProviderEndpoint(http.endpoint("/v1/chat/completions")), "acceptance-model",
            new GenerationParameters(Optional.empty(), Optional.empty(), Optional.empty()),
            new ExternalSecretReference("env:ACCEPTANCE_ONLY")
        );
    }

    private static ValidatedWorldmindConfiguration configuration(ProviderConfiguration provider) {
        return new ValidatedWorldmindConfiguration(
            new WorldmindGlobalConfiguration(1, true, "acceptance", provider, new ChatBatchingConfiguration(1, 5_000, 4_000)),
            new WorldmindProfile(1, "Aster", "Guide", "Be kind.",
                List.of(new LoreMaterial("lore/acceptance.md", "The observatory watches the valley.")),
                "brief", new ResponseLengthLimit(280))
        );
    }

    private static String response(String content) {
        return "{\"choices\":[{\"message\":{\"content\":\"" + content.replace("\n", "\\n") + "\"}}]}";
    }

    private static <T> T join(java.util.concurrent.CompletionStage<T> stage) { return stage.toCompletableFuture().join(); }

    private static final class RecordingSink implements ServerChatSink {
        private final List<Text> broadcasts = new ArrayList<>();
        @Override public void broadcast(Text message) { broadcasts.add(message); }
        @Override public boolean sendPrivate(UUID playerId, Text message) { return true; }
    }
}
