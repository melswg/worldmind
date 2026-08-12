package io.github.melswg.worldmind.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melswg.worldmind.core.configuration.ChatBatchingConfiguration;
import io.github.melswg.worldmind.core.conversation.AddressingSignal;
import io.github.melswg.worldmind.core.conversation.ChatBatchAdmission;
import io.github.melswg.worldmind.core.conversation.ChatBatchCoordinator;
import io.github.melswg.worldmind.core.conversation.ChatBatchSealReason;
import io.github.melswg.worldmind.core.conversation.SealedChatBatch;
import io.github.melswg.worldmind.core.conversation.SealedChatBatchConsumer;
import io.github.melswg.worldmind.core.conversation.ServerRequester;
import io.github.melswg.worldmind.core.conversation.UntrustedContext;
import io.github.melswg.worldmind.core.conversation.WorldIdentity;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

class ChatBatchAcceptanceTest {
    private static final WorldIdentity FIRST_WORLD = new WorldIdentity("save-one");
    private static final WorldIdentity SECOND_WORLD = new WorldIdentity("save-two");
    private static final ServerRequester PLAYER = new ServerRequester(
        UUID.fromString("6eac0fe2-1ce7-47fc-9fb7-cd98778b467a"),
        "Mira"
    );

    @Test
    void observesVanillaStyleMessageWithoutMutatingItAndDoesNotCallTheTicket06LanguageModel() {
        WorldmindAcceptanceScenario scenario = WorldmindTestkit.scenario();
        RecordingConsumer consumer = new RecordingConsumer();
        ChatBatchCoordinator batcher = batcher(scenario, new ChatBatchingConfiguration(8, 5_000, 4_000), consumer);
        String originalVanillaMessage = "Майни!";

        assertEquals(ChatBatchAdmission.SEALED_FOR_HANDOFF, observe(batcher, FIRST_WORLD, originalVanillaMessage));

        SealedChatBatch batch = consumer.onlyReceived();
        assertEquals(originalVanillaMessage, batch.messages().get(0).message());
        assertEquals(AddressingSignal.EXACT, batch.messages().get(0).addressingSignal());
        assertEquals(PLAYER.playerId(), batch.messages().get(0).requester().playerId());
        assertEquals("Mira", batch.messages().get(0).requester().playerName());
        assertEquals(FIRST_WORLD, batch.worldIdentity());
        assertEquals(List.of(1L), batch.messages().stream().map(message -> message.sequence()).toList());
        assertEquals("vanilla-game-context", batch.currentContextSnapshot().get(0).source());
        assertFalse(scenario.languageModel().receivedRequests().size() > 0);
        assertTrue(batch.messages().stream().noneMatch(message ->
            message.getClass().getPackageName().startsWith("net.minecraft")
        ));
    }

    @Test
    void isolatesWorldsAndKeepsPerWorldSequencesIndependent() {
        WorldmindAcceptanceScenario scenario = WorldmindTestkit.scenario();
        RecordingConsumer consumer = new RecordingConsumer();
        ChatBatchCoordinator batcher = batcher(scenario, new ChatBatchingConfiguration(8, 5_000, 4_000), consumer);

        observe(batcher, FIRST_WORLD, "Майни");
        observe(batcher, SECOND_WORLD, "Майни");

        assertEquals(2, consumer.received().size());
        assertEquals(FIRST_WORLD, consumer.received().get(0).worldIdentity());
        assertEquals(SECOND_WORLD, consumer.received().get(1).worldIdentity());
        assertEquals(1L, consumer.received().get(0).messages().get(0).sequence());
        assertEquals(1L, consumer.received().get(1).messages().get(0).sequence());
    }

    @Test
    void likelyAddressFlushesAmbientChatTogetherWithoutAssertingThatAReplyIsDue() {
        WorldmindAcceptanceScenario scenario = WorldmindTestkit.scenario();
        RecordingConsumer consumer = new RecordingConsumer();
        ChatBatchCoordinator batcher = batcher(scenario, new ChatBatchingConfiguration(8, 5_000, 4_000), consumer);

        assertEquals(ChatBatchAdmission.ACCEPTED_PENDING, observe(batcher, FIRST_WORLD, "у костра тихо"));
        assertEquals(ChatBatchAdmission.SEALED_FOR_HANDOFF, observe(batcher, FIRST_WORLD, "майне"));

        SealedChatBatch batch = consumer.onlyReceived();
        assertEquals(ChatBatchSealReason.ADDRESSING_SIGNAL, batch.sealReason());
        assertEquals(List.of("у костра тихо", "майне"), batch.messages().stream().map(message -> message.message()).toList());
        assertEquals(List.of(AddressingSignal.NONE, AddressingSignal.LIKELY),
            batch.messages().stream().map(message -> message.addressingSignal()).toList());
        assertFalse(scenario.languageModel().receivedRequests().size() > 0);
    }

    @Test
    void countAndEstimatedSizeLimitsIncludeTheTriggeringMessageWhole() {
        WorldmindAcceptanceScenario countScenario = WorldmindTestkit.scenario();
        RecordingConsumer countConsumer = new RecordingConsumer();
        ChatBatchCoordinator countBatcher = batcher(countScenario, new ChatBatchingConfiguration(2, 5_000, 4_000), countConsumer);
        observe(countBatcher, FIRST_WORLD, "one");
        observe(countBatcher, FIRST_WORLD, "two");
        assertEquals(ChatBatchSealReason.MAXIMUM_MESSAGE_COUNT, countConsumer.onlyReceived().sealReason());
        assertEquals(List.of("one", "two"), countConsumer.onlyReceived().messages().stream().map(message -> message.message()).toList());

        WorldmindAcceptanceScenario sizeScenario = WorldmindTestkit.scenario();
        RecordingConsumer sizeConsumer = new RecordingConsumer();
        ChatBatchCoordinator sizeBatcher = batcher(sizeScenario, new ChatBatchingConfiguration(8, 5_000, 1), sizeConsumer);
        String oversized = "это сообщение не усекается";
        observe(sizeBatcher, FIRST_WORLD, oversized);
        assertEquals(ChatBatchSealReason.MAXIMUM_ESTIMATED_INPUT_SIZE, sizeConsumer.onlyReceived().sealReason());
        assertEquals(oversized, sizeConsumer.onlyReceived().messages().get(0).message());
    }

    @Test
    void maxWaitStartsAtTheFirstMessageAndCancelledStaleDeadlineCannotSealANewBatch() {
        WorldmindAcceptanceScenario scenario = WorldmindTestkit.scenario();
        RecordingConsumer consumer = new RecordingConsumer();
        ChatBatchCoordinator batcher = batcher(scenario, new ChatBatchingConfiguration(8, 5_000, 4_000), consumer);

        observe(batcher, FIRST_WORLD, "first");
        scenario.clock().advanceBy(Duration.ofSeconds(2));
        observe(batcher, FIRST_WORLD, "second");
        scenario.clock().advanceBy(Duration.ofSeconds(3));
        scenario.serverScheduler().runDueTasks();
        assertEquals(1, consumer.received().size());
        assertEquals(ChatBatchSealReason.MAXIMUM_WAIT, consumer.onlyReceived().sealReason());
        assertEquals(List.of("first", "second"), consumer.onlyReceived().messages().stream().map(message -> message.message()).toList());

        consumer.complete(0);
        scenario.clock().advanceBy(Duration.ofSeconds(1));
        observe(batcher, FIRST_WORLD, "new pending");
        scenario.clock().advanceBy(Duration.ofSeconds(4));
        scenario.serverScheduler().runDueTasks();
        assertEquals(1, consumer.received().size());
        scenario.clock().advanceBy(Duration.ofSeconds(1));
        scenario.serverScheduler().runDueTasks();
        assertEquals(2, consumer.received().size());
        assertEquals(List.of("new pending"), consumer.received().get(1).messages().stream().map(message -> message.message()).toList());
    }

    @Test
    void messagesDuringInflightOwnershipBecomeOneOrderedNextBatchAndCapacityIsExplicit() {
        WorldmindAcceptanceScenario scenario = WorldmindTestkit.scenario();
        RecordingConsumer consumer = new RecordingConsumer();
        ChatBatchCoordinator batcher = batcher(scenario, new ChatBatchingConfiguration(8, 5_000, 4_000), consumer);

        observe(batcher, FIRST_WORLD, "Майни");
        observe(batcher, FIRST_WORLD, "ambient while in flight");
        observe(batcher, FIRST_WORLD, "Майни");
        assertEquals(ChatBatchAdmission.REJECTED_CAPACITY, observe(batcher, FIRST_WORLD, "cannot become a third batch"));

        assertEquals(1, consumer.received().size());
        consumer.complete(0);
        assertEquals(2, consumer.received().size());
        assertEquals(
            List.of("ambient while in flight", "Майни"),
            consumer.received().get(1).messages().stream().map(message -> message.message()).toList()
        );
        assertEquals(List.of(2L, 3L), consumer.received().get(1).messages().stream().map(message -> message.sequence()).toList());
    }

    @Test
    void failedAndLateConsumerCompletionsReleaseOrIgnoreOwnershipWithoutEscapingTheChatPath() {
        WorldmindAcceptanceScenario scenario = WorldmindTestkit.scenario();
        RecordingConsumer consumer = new RecordingConsumer();
        ChatBatchCoordinator batcher = batcher(scenario, new ChatBatchingConfiguration(8, 5_000, 4_000), consumer);

        observe(batcher, FIRST_WORLD, "Майни");
        observe(batcher, FIRST_WORLD, "Майни");
        consumer.fail(0);
        assertEquals(2, consumer.received().size());
        assertEquals(List.of(2L), consumer.received().get(1).messages().stream().map(message -> message.sequence()).toList());

        batcher.close();
        consumer.complete(1);
        assertEquals(ChatBatchAdmission.IGNORED_AFTER_CLOSE, observe(batcher, FIRST_WORLD, "after stop"));
    }

    @Test
    void shutdownCancelsPendingTimersAndDoesNotInvokeTheConsumerAfterTheRuntimeStops() {
        WorldmindAcceptanceScenario scenario = WorldmindTestkit.scenario();
        RecordingConsumer consumer = new RecordingConsumer();
        ChatBatchCoordinator batcher = batcher(scenario, new ChatBatchingConfiguration(8, 5_000, 4_000), consumer);

        observe(batcher, FIRST_WORLD, "ambient");
        assertEquals(1, scenario.serverScheduler().pendingDelayedTaskCount());
        batcher.close();
        assertEquals(0, scenario.serverScheduler().pendingDelayedTaskCount());
        scenario.clock().advanceBy(Duration.ofSeconds(5));
        scenario.serverScheduler().runDueTasks();
        assertTrue(consumer.received().isEmpty());
    }

    private ChatBatchCoordinator batcher(
        WorldmindAcceptanceScenario scenario,
        ChatBatchingConfiguration configuration,
        RecordingConsumer consumer
    ) {
        return scenario.chatBatcher(configuration, "Майни", consumer);
    }

    private ChatBatchAdmission observe(ChatBatchCoordinator batcher, WorldIdentity world, String message) {
        return batcher.observe(
            world,
            PLAYER,
            message,
            List.of(new UntrustedContext("vanilla-game-context", "dimension=minecraft:overworld; gameTime=0; weather=clear"))
        );
    }

    private static final class RecordingConsumer implements SealedChatBatchConsumer {
        private final List<SealedChatBatch> received = new ArrayList<>();
        private final List<CompletableFuture<Void>> completions = new ArrayList<>();

        @Override
        public CompletionStage<?> accept(SealedChatBatch batch) {
            received.add(batch);
            CompletableFuture<Void> completion = new CompletableFuture<>();
            completions.add(completion);
            return completion;
        }

        List<SealedChatBatch> received() {
            return received;
        }

        SealedChatBatch onlyReceived() {
            assertEquals(1, received.size());
            return received.get(0);
        }

        void complete(int index) {
            completions.get(index).complete(null);
        }

        void fail(int index) {
            completions.get(index).completeExceptionally(new IllegalStateException("deterministic consumer failure"));
        }
    }
}
