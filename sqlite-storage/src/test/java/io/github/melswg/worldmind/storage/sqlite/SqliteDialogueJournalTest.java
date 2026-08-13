package io.github.melswg.worldmind.storage.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.melswg.worldmind.core.conversation.AddressingSignal;
import io.github.melswg.worldmind.core.conversation.CapturedPublicChatMessage;
import io.github.melswg.worldmind.core.conversation.ChatBatchSealReason;
import io.github.melswg.worldmind.core.conversation.DirectReply;
import io.github.melswg.worldmind.core.conversation.SealedChatBatch;
import io.github.melswg.worldmind.core.conversation.ServerRequester;
import io.github.melswg.worldmind.core.administration.MemoryInspectionQuery;
import io.github.melswg.worldmind.core.administration.MemoryInspectionScope;
import io.github.melswg.worldmind.core.administration.MemoryRecordType;
import io.github.melswg.worldmind.core.journal.JournalBatchOutcome;
import io.github.melswg.worldmind.core.journal.JournalDeliveryReport;
import io.github.melswg.worldmind.core.journal.JournalDeliveryStatus;
import io.github.melswg.worldmind.core.journal.JournalParticipationDecision;
import io.github.melswg.worldmind.core.journal.ProviderAttemptOutcome;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteDialogueJournalTest {
    private static final ServerRequester MIRA = new ServerRequester(
        UUID.fromString("6eac0fe2-1ce7-47fc-9fb7-cd98778b467a"), "Mira"
    );

    @TempDir Path temporaryDirectory;

    @Test
    void persistsSequencesWorldIdentityBatchMembershipAndDeliveredReplyAcrossRestart() throws Exception {
        Path database = temporaryDirectory.resolve("save-one/worldmind/" + SqliteDialogueJournal.DATABASE_FILE_NAME);
        SqliteDialogueJournal first = open(database);
        var firstMessage = join(first.appendObservation(message("The fire is low.", AddressingSignal.NONE)));
        var secondMessage = join(first.appendObservation(message("Aster!", AddressingSignal.EXACT)));
        var batch = join(first.appendBatch(new SealedChatBatch(
            first.openedWorldIdentity(),
            List.of(firstMessage.toObservedPublicChatMessage(List.of()), secondMessage.toObservedPublicChatMessage(List.of())),
            ChatBatchSealReason.ADDRESSING_SIGNAL,
            List.of()
        )));
        join(first.appendOutcome(new JournalBatchOutcome(
            batch.batchId(), ProviderAttemptOutcome.SUCCEEDED,
            Optional.of(JournalParticipationDecision.DIRECT_REPLY), Optional.empty(),
            new JournalDeliveryReport(JournalDeliveryStatus.PUBLIC_REPLY_DELIVERED, Optional.of("The cave is east.")), Instant.EPOCH
        )));
        var originalWorld = first.openedWorldIdentity();
        join(first.closeAsync());

        SqliteDialogueJournal reopened = open(database);
        var snapshot = join(reopened.readSnapshot());
        assertEquals(originalWorld, reopened.openedWorldIdentity());
        assertEquals(List.of(1L, 2L), snapshot.observations().stream().map(row -> row.sequence()).toList());
        assertEquals(List.of("The fire is low.", "Aster!"), snapshot.observations().stream().map(row -> row.text()).toList());
        assertEquals(List.of(1L, 2L), snapshot.batches().get(0).messageSequences());
        assertEquals(JournalParticipationDecision.DIRECT_REPLY, snapshot.outcomes().get(batch.batchId()).decision().orElseThrow());
        assertEquals("The cave is east.", snapshot.outcomes().get(batch.batchId()).delivery().deliveredResponse().orElseThrow());
        assertEquals(3L, join(reopened.appendObservation(message("After restart", AddressingSignal.NONE))).sequence());
        join(reopened.closeAsync());
        assertFalse(new String(Files.readAllBytes(database)).contains("WORLDMIND_ACCEPTANCE_KEY"));
    }

    @Test
    void isolatesDifferentWorldDatabasesAndRejectsWritesAfterClose() {
        SqliteDialogueJournal first = open(temporaryDirectory.resolve("first/worldmind/worldmind.sqlite3"));
        SqliteDialogueJournal second = open(temporaryDirectory.resolve("second/worldmind/worldmind.sqlite3"));
        join(first.appendObservation(message("first", AddressingSignal.NONE)));
        join(second.appendObservation(message("second", AddressingSignal.NONE)));
        assertEquals("first", join(first.readSnapshot()).observations().get(0).text());
        assertEquals("second", join(second.readSnapshot()).observations().get(0).text());
        assertFalse(first.openedWorldIdentity().equals(second.openedWorldIdentity()));
        join(first.closeAsync());
        assertThrows(CompletionException.class, () -> join(first.appendObservation(message("closed", AddressingSignal.NONE))));
        join(second.closeAsync());
    }

    @Test
    void readsInspectionThroughBoundedKeysetPagesAndExcludesMixedBatchesFromPlayerScope() {
        SqliteDialogueJournal journal = open(temporaryDirectory.resolve("inspection/worldmind/worldmind.sqlite3"));
        ServerRequester other = new ServerRequester(UUID.fromString("922ec6b5-623e-42a2-a893-d3892615b7e6"), "Mira");
        for (int index = 0; index < 6; index++) {
            join(journal.appendObservation(new CapturedPublicChatMessage(MIRA, "Mira message " + index,
                AddressingSignal.NONE, Instant.ofEpochMilli(index), List.of())));
        }
        var firstPage = join(journal.inspect(new MemoryInspectionQuery(
            MemoryInspectionScope.player(MIRA.playerId()), MemoryRecordType.OBSERVATION, Optional.empty()
        )));
        assertEquals(5, firstPage.records().size());
        var secondPage = join(journal.inspect(new MemoryInspectionQuery(
            MemoryInspectionScope.player(MIRA.playerId()), MemoryRecordType.OBSERVATION, firstPage.next()
        )));
        assertEquals(1, secondPage.records().size());
        assertEquals(List.of(6L, 5L, 4L, 3L, 2L), firstPage.records().stream().map(value -> value.lastSequence()).toList());
        assertEquals(1L, secondPage.records().get(0).lastSequence());

        var one = join(journal.appendObservation(new CapturedPublicChatMessage(MIRA, "one", AddressingSignal.NONE, Instant.EPOCH, List.of())));
        var two = join(journal.appendObservation(new CapturedPublicChatMessage(other, "two", AddressingSignal.NONE, Instant.EPOCH, List.of())));
        var mixed = join(journal.appendBatch(new SealedChatBatch(journal.openedWorldIdentity(), List.of(
            one.toObservedPublicChatMessage(List.of()), two.toObservedPublicChatMessage(List.of())
        ), ChatBatchSealReason.ADDRESSING_SIGNAL, List.of())));
        join(journal.appendOutcome(new JournalBatchOutcome(mixed.batchId(), ProviderAttemptOutcome.SUCCEEDED,
            Optional.of(JournalParticipationDecision.DIRECT_REPLY), Optional.empty(),
            new JournalDeliveryReport(JournalDeliveryStatus.PUBLIC_REPLY_DELIVERED, Optional.of("must not leak")), Instant.EPOCH)));
        var exclusive = join(journal.appendBatch(new SealedChatBatch(journal.openedWorldIdentity(), List.of(
            join(journal.appendObservation(new CapturedPublicChatMessage(MIRA, "three", AddressingSignal.NONE, Instant.EPOCH, List.of())))
                .toObservedPublicChatMessage(List.of())
        ), ChatBatchSealReason.ADDRESSING_SIGNAL, List.of())));
        join(journal.appendOutcome(new JournalBatchOutcome(exclusive.batchId(), ProviderAttemptOutcome.SUCCEEDED,
            Optional.of(JournalParticipationDecision.DIRECT_REPLY), Optional.empty(),
            new JournalDeliveryReport(JournalDeliveryStatus.PUBLIC_REPLY_DELIVERED, Optional.of("safe reply")), Instant.EPOCH)));

        var playerBatches = join(journal.inspect(new MemoryInspectionQuery(
            MemoryInspectionScope.player(MIRA.playerId()), MemoryRecordType.BATCH, Optional.empty()
        )));
        assertEquals(List.of("batch:" + exclusive.batchId()), playerBatches.records().stream().map(value -> value.stableIdentity()).toList());
        var playerReplies = join(journal.inspect(new MemoryInspectionQuery(
            MemoryInspectionScope.player(MIRA.playerId()), MemoryRecordType.REPLY, Optional.empty()
        )));
        assertEquals(List.of("reply:" + exclusive.batchId()), playerReplies.records().stream().map(value -> value.stableIdentity()).toList());
        assertEquals("safe reply", join(journal.detail(MemoryInspectionScope.player(MIRA.playerId()), MemoryRecordType.REPLY,
            "reply:" + exclusive.batchId())).orElseThrow().text());
        assertFalse(join(journal.detail(MemoryInspectionScope.player(MIRA.playerId()), MemoryRecordType.REPLY,
            "reply:" + mixed.batchId())).isPresent());
        join(journal.closeAsync());
    }

    private SqliteDialogueJournal open(Path path) { return join(SqliteDialogueJournal.open(path)); }

    private CapturedPublicChatMessage message(String text, AddressingSignal signal) {
        return new CapturedPublicChatMessage(MIRA, text, signal, Instant.EPOCH, List.of());
    }

    private static <T> T join(java.util.concurrent.CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }
}
