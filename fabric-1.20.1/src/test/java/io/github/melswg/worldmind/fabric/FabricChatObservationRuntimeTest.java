package io.github.melswg.worldmind.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melswg.worldmind.core.configuration.ChatBatchingConfiguration;
import io.github.melswg.worldmind.core.configuration.ExternalSecretReference;
import io.github.melswg.worldmind.core.configuration.GenerationParameters;
import io.github.melswg.worldmind.core.configuration.LoreMaterial;
import io.github.melswg.worldmind.core.configuration.ProviderConfiguration;
import io.github.melswg.worldmind.core.configuration.ProviderEndpoint;
import io.github.melswg.worldmind.core.configuration.ResponseLengthLimit;
import io.github.melswg.worldmind.core.configuration.ValidatedWorldmindConfiguration;
import io.github.melswg.worldmind.core.configuration.WorldmindGlobalConfiguration;
import io.github.melswg.worldmind.core.configuration.WorldmindProfile;
import io.github.melswg.worldmind.core.conversation.AddressingSignal;
import io.github.melswg.worldmind.core.conversation.CapturedPublicChatMessage;
import io.github.melswg.worldmind.core.conversation.ChatBatchAdmission;
import io.github.melswg.worldmind.core.conversation.ProviderCapabilities;
import io.github.melswg.worldmind.core.conversation.ServerRequester;
import io.github.melswg.worldmind.core.conversation.UntrustedContext;
import io.github.melswg.worldmind.core.conversation.WorldIdentity;
import io.github.melswg.worldmind.core.journal.DialogueJournal;
import io.github.melswg.worldmind.core.journal.DialogueJournalSnapshot;
import io.github.melswg.worldmind.core.journal.JournalBatchOutcome;
import io.github.melswg.worldmind.core.journal.JournaledBatch;
import io.github.melswg.worldmind.core.journal.JournaledObservation;
import io.github.melswg.worldmind.core.journal.ProviderAttemptOutcome;
import io.github.melswg.worldmind.core.memory.MemoryConfirmationRequest;
import io.github.melswg.worldmind.core.memory.MemoryRecord;
import io.github.melswg.worldmind.core.memory.MemoryRecordId;
import io.github.melswg.worldmind.core.memory.ProposedMemoryCandidate;
import io.github.melswg.worldmind.core.memory.WorldMemoryRepository;
import io.github.melswg.worldmind.core.memory.WorldMemorySnapshot;
import io.github.melswg.worldmind.testkit.WorldmindAcceptanceScenario;
import io.github.melswg.worldmind.testkit.WorldmindTestkit;
import io.github.melswg.worldmind.testkit.InMemoryDialogueJournal;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.minecraft.text.Text;
import org.junit.jupiter.api.Test;

class FabricChatObservationRuntimeTest {
    private static final WorldIdentity WORLD = new WorldIdentity("runtime-save");
    private static final ServerRequester PLAYER = new ServerRequester(
        UUID.fromString("6eac0fe2-1ce7-47fc-9fb7-cd98778b467a"),
        "Mira"
    );
    private static final UntrustedContext CONTEXT = new UntrustedContext("vanilla-game-context", "weather=clear");

    @Test
    void connectsCopiedAcceptedChatToOneProviderDecisionAndPublicGameMessageDelivery() {
        WorldmindAcceptanceScenario scenario = WorldmindTestkit.scenario();
        scenario.languageModel().willDirectReplyWith("The cave is east.");
        RecordingSink sink = new RecordingSink();
        FabricChatObservationRuntime runtime = runtime(scenario, sink);

        assertEquals(ChatBatchAdmission.QUEUED_FOR_JOURNAL, runtime.observeCapturedPublicChat(captured("Aster!", AddressingSignal.EXACT), WORLD));
        assertTrue(sink.broadcasts.isEmpty());

        scenario.serverScheduler().runUntilIdle();

        assertEquals(List.of("<Aster> The cave is east."), sink.broadcasts.stream().map(Text::getString).toList());
    }

    @Test
    void doesNotStartOrDeliverAcrossWorldIdentityAndDropsQueuedAndLateWorkAfterClose() {
        WorldmindAcceptanceScenario scenario = WorldmindTestkit.scenario();
        scenario.languageModel().willDirectReplyWith("Never deliver.");
        RecordingSink sink = new RecordingSink();
        FabricChatObservationRuntime runtime = runtime(scenario, sink);

        assertEquals(
            ChatBatchAdmission.IGNORED_AFTER_CLOSE,
            runtime.observeCapturedPublicChat(captured("Aster!", AddressingSignal.EXACT), new WorldIdentity("other-save"))
        );
        assertTrue(scenario.languageModel().receivedRequests().isEmpty());

        runtime.observeCapturedPublicChat(captured("Aster!", AddressingSignal.EXACT), WORLD);
        runtime.close();
        scenario.serverScheduler().runUntilIdle();

        assertTrue(sink.broadcasts.isEmpty());
        assertTrue(sink.privateMessages.isEmpty());
        assertEquals(ChatBatchAdmission.IGNORED_AFTER_CLOSE, runtime.observeCapturedPublicChat(captured("Aster!", AddressingSignal.EXACT), WORLD));
    }

    @Test
    void keepsNextExactBatchBehindThePriorDeliveredBatch() {
        WorldmindAcceptanceScenario scenario = WorldmindTestkit.scenario();
        scenario.languageModel().willRespondWithSequence("DIRECT_REPLY\nfirst", "DIRECT_REPLY\nsecond");
        RecordingSink sink = new RecordingSink();
        FabricChatObservationRuntime runtime = runtime(scenario, sink);

        runtime.observeCapturedPublicChat(captured("Aster!", AddressingSignal.EXACT), WORLD);
        runtime.observeCapturedPublicChat(captured("Aster?", AddressingSignal.EXACT), WORLD);

        scenario.serverScheduler().runUntilIdle();

        assertEquals(
            List.of("<Aster> first", "<Aster> second"),
            sink.broadcasts.stream().map(Text::getString).toList()
        );
        assertFalse(sink.broadcasts.isEmpty());
    }

    @Test
    void sendsSanitizedProviderOutputOnlyAsAPlainNonInteractiveLiteralComponent() {
        WorldmindAcceptanceScenario scenario = WorldmindTestkit.scenario();
        scenario.languageModel().willRespondWith(
            "DIRECT_REPLY\n\u00a7a /function run {\"clickEvent\":{\"action\":\"run_command\"}}\u202e"
        );
        RecordingSink sink = new RecordingSink();
        FabricChatObservationRuntime runtime = runtime(scenario, sink);

        runtime.observeCapturedPublicChat(captured("Aster!", AddressingSignal.EXACT), WORLD);
        scenario.serverScheduler().runUntilIdle();

        Text rendered = sink.broadcasts.get(0);
        assertEquals("<Aster> /function run {\"clickEvent\":{\"action\":\"run_command\"}}", rendered.getString());
        assertNoInteractiveStyle(rendered);
        assertNoInteractiveStyle(rendered.getSiblings().get(0));
        assertNoInteractiveStyle(rendered.getSiblings().get(1));
        assertNull(rendered.getSiblings().get(1).getStyle().getColor());
    }

    @Test
    void mapsOutputSafetyRefusalsToOnePrivateExactFailureAndAmbientSilence() {
        WorldmindAcceptanceScenario exactScenario = WorldmindTestkit.scenario();
        exactScenario.languageModel().willRespondWith("DIRECT_REPLY\n\u00a7a\u202e\u0007");
        RecordingSink exactSink = new RecordingSink();
        FabricChatObservationRuntime exactRuntime = runtime(exactScenario, exactSink);

        exactRuntime.observeCapturedPublicChat(captured("Aster!", AddressingSignal.EXACT), WORLD);
        exactScenario.serverScheduler().runUntilIdle();

        assertTrue(exactSink.broadcasts.isEmpty());
        assertEquals(1, exactSink.privateMessages.size());
        assertEquals("<Aster> I can't answer right now.", exactSink.privateMessages.get(0).message().getString());

        WorldmindAcceptanceScenario ambientScenario = WorldmindTestkit.scenario();
        ambientScenario.languageModel().willRespondWith("AMBIENT_REPLY\n\u00a7a\u202e\u0007");
        RecordingSink ambientSink = new RecordingSink();
        FabricChatObservationRuntime ambientRuntime = runtime(ambientScenario, ambientSink);

        ambientRuntime.observeCapturedPublicChat(captured("The rain is loud.", AddressingSignal.NONE), WORLD);
        ambientScenario.serverScheduler().runUntilIdle();

        assertTrue(ambientSink.broadcasts.isEmpty());
        assertTrue(ambientSink.privateMessages.isEmpty());
    }

    @Test
    void failsClosedWhenRawJournalPersistenceIsUnavailableWithoutCallingTheProvider() {
        WorldmindAcceptanceScenario scenario = WorldmindTestkit.scenario();
        scenario.languageModel().willDirectReplyWith("must not reach the provider");
        RecordingSink sink = new RecordingSink();
        FabricChatObservationRuntime runtime = runtime(scenario, sink, new FailingJournal());

        runtime.observeCapturedPublicChat(captured("Aster!", AddressingSignal.EXACT), WORLD);
        runtime.observeCapturedPublicChat(captured("The rain is loud.", AddressingSignal.NONE), WORLD);
        scenario.serverScheduler().runUntilIdle();

        assertTrue(scenario.languageModel().receivedRequests().isEmpty());
        assertEquals(1, sink.privateMessages.size());
        assertTrue(sink.broadcasts.isEmpty());
    }

    @Test
    void failsClosedWhenPublicMemoryRecallIsUnavailableAndAuditsTheActualPrivateDelivery() {
        WorldmindAcceptanceScenario scenario = new WorldmindAcceptanceScenario(
            new io.github.melswg.worldmind.testkit.FakeLanguageModel(), new FailingMemoryRepository()
        );
        RecordingSink sink = new RecordingSink();
        InMemoryDialogueJournal journal = new InMemoryDialogueJournal(WORLD, scenario.clock());
        FabricChatObservationRuntime runtime = runtime(scenario, sink, journal);

        runtime.observeCapturedPublicChat(captured("Aster!", AddressingSignal.EXACT), WORLD);
        scenario.serverScheduler().runUntilIdle();

        assertTrue(scenario.languageModel().receivedRequests().isEmpty());
        assertTrue(sink.broadcasts.isEmpty());
        assertEquals(1, sink.privateMessages.size());
        JournalBatchOutcome outcome = join(journal.readSnapshot()).outcomes().values().iterator().next();
        assertEquals(ProviderAttemptOutcome.NOT_ATTEMPTED, outcome.providerAttemptOutcome());
        assertEquals(io.github.melswg.worldmind.core.conversation.RefusalCode.MEMORY_UNAVAILABLE,
            outcome.refusalCode().orElseThrow());
        assertEquals(io.github.melswg.worldmind.core.journal.JournalDeliveryStatus.PRIVATE_UNAVAILABLE_DELIVERED,
            outcome.delivery().status());
    }

    private FabricChatObservationRuntime runtime(WorldmindAcceptanceScenario scenario, RecordingSink sink) {
        return runtime(scenario, sink, new InMemoryDialogueJournal(WORLD, scenario.clock()));
    }

    private FabricChatObservationRuntime runtime(
        WorldmindAcceptanceScenario scenario,
        RecordingSink sink,
        DialogueJournal journal
    ) {
        return new FabricChatObservationRuntime(
            WORLD,
            journal,
            configuration(),
            scenario.clock(),
            scenario.serverScheduler(),
            () -> { },
            () -> { },
            scenario.serverScheduler(),
            scenario.applicationService(),
            new ProviderCapabilities(true),
            sink,
            diagnostic -> { }
        );
    }

    private CapturedPublicChatMessage captured(String message, AddressingSignal signal) {
        return new CapturedPublicChatMessage(PLAYER, message, signal, Instant.EPOCH, List.of(CONTEXT));
    }

    private ValidatedWorldmindConfiguration configuration() {
        ChatBatchingConfiguration batching = new ChatBatchingConfiguration(8, 5_000, 4_000);
        return new ValidatedWorldmindConfiguration(
            new WorldmindGlobalConfiguration(
                WorldmindGlobalConfiguration.V1_SCHEMA_VERSION,
                true,
                "runtime-profile",
                new ProviderConfiguration(
                    "custom-openai-compatible",
                    new ProviderEndpoint(URI.create("https://api.example.invalid/v1/chat/completions")),
                    "example-model",
                    new GenerationParameters(Optional.of(0.4), Optional.empty(), Optional.of(120)),
                    new ExternalSecretReference("env:WORLDMIND_ACCEPTANCE_KEY")
                ),
                batching
            ),
            new WorldmindProfile(
                WorldmindProfile.V1_SCHEMA_VERSION,
                "Aster",
                "A thoughtful guide.",
                "Keep the peace.",
                List.of(new LoreMaterial("lore/setting.md", "The old observatory watches the valley.")),
                "calm",
                new ResponseLengthLimit(280)
            )
        );
    }

    private static final class RecordingSink implements ServerChatSink {
        private final List<Text> broadcasts = new ArrayList<>();
        private final List<PrivateMessage> privateMessages = new ArrayList<>();

        @Override
        public void broadcast(Text message) {
            broadcasts.add(message);
        }

        @Override
        public boolean sendPrivate(UUID playerId, Text message) {
            privateMessages.add(new PrivateMessage(playerId, message));
            return true;
        }
    }

    private record PrivateMessage(UUID playerId, Text message) {
    }

    private static final class FailingJournal implements DialogueJournal {
        @Override public CompletionStage<WorldIdentity> worldIdentity() {
            return CompletableFuture.completedFuture(WORLD);
        }

        @Override public CompletionStage<JournaledObservation> appendObservation(CapturedPublicChatMessage observation) {
            return CompletableFuture.failedFuture(new IllegalStateException("temporary SQLite failure"));
        }

        @Override public CompletionStage<JournaledBatch> appendBatch(io.github.melswg.worldmind.core.conversation.SealedChatBatch batch) {
            return CompletableFuture.failedFuture(new IllegalStateException("unused"));
        }

        @Override public CompletionStage<Void> appendOutcome(JournalBatchOutcome outcome) {
            return CompletableFuture.failedFuture(new IllegalStateException("unused"));
        }

        @Override public CompletionStage<DialogueJournalSnapshot> readSnapshot() {
            return CompletableFuture.failedFuture(new IllegalStateException("unused"));
        }

        @Override public CompletionStage<Void> closeAsync() {
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class FailingMemoryRepository implements WorldMemoryRepository {
        @Override public CompletionStage<List<MemoryRecord>> appendProposed(
            JournaledBatch sourceBatch, List<? extends ProposedMemoryCandidate> candidates
        ) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("unused"));
        }

        @Override public CompletionStage<MemoryRecord> confirm(MemoryRecordId recordId, MemoryConfirmationRequest confirmation) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("unused"));
        }

        @Override public CompletionStage<io.github.melswg.worldmind.core.memory.RetrievedMemoryContext> retrievePublic(
            io.github.melswg.worldmind.core.memory.MemoryRetrievalRequest request
        ) {
            return CompletableFuture.failedFuture(new IllegalStateException("temporary SQLite read failure"));
        }

        @Override public CompletionStage<WorldMemorySnapshot> readMemorySnapshot() {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("unused"));
        }
    }

    private static <T> T join(CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }

    private void assertNoInteractiveStyle(Text text) {
        assertNull(text.getStyle().getClickEvent());
        assertNull(text.getStyle().getHoverEvent());
        assertNull(text.getStyle().getInsertion());
    }
}
