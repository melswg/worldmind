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

    private SqliteDialogueJournal open(Path path) { return join(SqliteDialogueJournal.open(path)); }

    private CapturedPublicChatMessage message(String text, AddressingSignal signal) {
        return new CapturedPublicChatMessage(MIRA, text, signal, Instant.EPOCH, List.of());
    }

    private static <T> T join(java.util.concurrent.CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }
}
