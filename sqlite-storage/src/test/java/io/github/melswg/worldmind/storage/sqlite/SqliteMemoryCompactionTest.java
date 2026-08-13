package io.github.melswg.worldmind.storage.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melswg.worldmind.core.conversation.AddressingSignal;
import io.github.melswg.worldmind.core.conversation.CapturedPublicChatMessage;
import io.github.melswg.worldmind.core.conversation.ChatBatchSealReason;
import io.github.melswg.worldmind.core.conversation.SealedChatBatch;
import io.github.melswg.worldmind.core.conversation.ServerRequester;
import io.github.melswg.worldmind.core.journal.JournalBatchOutcome;
import io.github.melswg.worldmind.core.journal.JournalDeliveryReport;
import io.github.melswg.worldmind.core.journal.ProviderAttemptOutcome;
import io.github.melswg.worldmind.core.memory.DerivedMemoryCandidate;
import io.github.melswg.worldmind.core.memory.MemoryCompactionInput;
import io.github.melswg.worldmind.core.memory.MemoryCompactionResult;
import io.github.melswg.worldmind.core.memory.MemoryConfidence;
import io.github.melswg.worldmind.core.memory.MemoryImportance;
import io.github.melswg.worldmind.core.memory.MemoryScope;
import io.github.melswg.worldmind.core.memory.MemoryVisibility;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteMemoryCompactionTest {
    private static final ServerRequester MIRA = new ServerRequester(
        UUID.fromString("6eac0fe2-1ce7-47fc-9fb7-cd98778b467a"), "Mira"
    );

    @TempDir Path temporaryDirectory;

    @Test
    void compactsOnlyCompletedOldRangesAndKeepsAppendOnlyVersionedAuditHistoryAcrossRestart() {
        Path database = temporaryDirectory.resolve("compaction/worldmind.sqlite3");
        SqliteDialogueJournal journal = open(database);
        appendCompletedSingletonBatches(journal, 32);

        MemoryCompactionInput input = join(journal.nextCompaction(journal.openedWorldIdentity())).orElseThrow();
        assertEquals(1, input.provenance().sourceRange().firstSequence());
        assertEquals(8, input.provenance().sourceRange().lastSequence());
        assertEquals(8, input.sources().size());
        assertEquals(8, input.provenance().sourceBatchIds().size());

        var first = join(journal.persistCompaction(input, result("A storm gathered over the observatory.")));
        assertEquals(1, first.events().size());
        assertEquals(1, first.summaries().size());
        assertEquals(1, first.currentSituations().size());
        assertEquals(1, first.summaries().get(0).version());
        assertEquals(1, first.currentSituations().get(0).version());
        assertEquals(input.provenance(), first.summaries().get(0).provenance());
        assertEquals(input.provenance(), first.currentSituations().get(0).provenance());
        assertTrue(join(journal.nextCompaction(journal.openedWorldIdentity())).isEmpty(), "the only eligible old range is now covered");

        var second = join(journal.persistCompaction(input, result("The observatory remains under a gathering storm.")));
        assertEquals(1, second.events().size(), "same exact event is idempotent");
        assertEquals(2, second.summaries().size());
        assertEquals(List.of(1, 2), second.summaries().stream().map(value -> value.version()).toList());
        assertEquals(1, second.summaries().stream().map(value -> value.seriesId()).distinct().count());
        assertEquals(2, second.currentSituations().size());
        assertEquals(List.of(1, 2), second.currentSituations().stream().map(value -> value.version()).toList());
        assertEquals(1, second.currentSituations().stream().map(value -> value.seriesId()).distinct().count());

        var rawBeforeRestart = join(journal.readSnapshot());
        assertEquals(32, rawBeforeRestart.observations().size());
        assertEquals(32, rawBeforeRestart.batches().size());
        assertEquals(32, rawBeforeRestart.outcomes().size());
        join(journal.closeAsync());

        SqliteDialogueJournal reopened = open(database);
        var afterRestart = join(reopened.readCompactionSnapshot());
        assertEquals(second, afterRestart);
        var rawAfterRestart = join(reopened.readSnapshot());
        assertEquals(rawBeforeRestart.observations(), rawAfterRestart.observations());
        assertEquals(rawBeforeRestart.batches(), rawAfterRestart.batches());
        assertEquals(rawBeforeRestart.outcomes(), rawAfterRestart.outcomes());
        join(reopened.closeAsync());
    }

    @Test
    void rejectsInvalidGeneratedPayloadTransactionallyWithoutTouchingRawOrCurrentHistory() {
        SqliteDialogueJournal journal = open(temporaryDirectory.resolve("rollback/worldmind.sqlite3"));
        appendCompletedSingletonBatches(journal, 25);
        MemoryCompactionInput input = join(journal.nextCompaction(journal.openedWorldIdentity())).orElseThrow();
        var rawBefore = join(journal.readSnapshot());

        DerivedMemoryCandidate oversized = candidate("x".repeat(1_201));
        assertThrows(CompletionException.class, () -> join(journal.persistCompaction(
            input, new MemoryCompactionResult(List.of(), Optional.of(oversized), Optional.empty())
        )));

        var derived = join(journal.readCompactionSnapshot());
        assertTrue(derived.events().isEmpty());
        assertTrue(derived.summaries().isEmpty());
        assertTrue(derived.currentSituations().isEmpty());
        assertEquals(rawBefore, join(journal.readSnapshot()));
        join(journal.closeAsync());
    }

    private void appendCompletedSingletonBatches(SqliteDialogueJournal journal, int count) {
        for (int sequence = 1; sequence <= count; sequence++) {
            var observation = join(journal.appendObservation(new CapturedPublicChatMessage(
                MIRA, "message " + sequence, AddressingSignal.NONE, Instant.ofEpochSecond(sequence), List.of()
            )));
            var batch = join(journal.appendBatch(new SealedChatBatch(
                journal.openedWorldIdentity(), List.of(observation.toObservedPublicChatMessage(List.of())),
                ChatBatchSealReason.MAXIMUM_MESSAGE_COUNT, List.of()
            )));
            join(journal.appendOutcome(new JournalBatchOutcome(
                batch.batchId(), ProviderAttemptOutcome.NOT_ATTEMPTED, Optional.empty(), Optional.empty(),
                JournalDeliveryReport.noOutput(), Instant.ofEpochSecond(sequence)
            )));
        }
    }

    private MemoryCompactionResult result(String text) {
        return new MemoryCompactionResult(List.of(candidate("Event: observatory storm.")), Optional.of(candidate(text)), Optional.of(candidate(text)));
    }

    private DerivedMemoryCandidate candidate(String content) {
        return new DerivedMemoryCandidate(
            MemoryScope.world(), MemoryVisibility.PUBLIC, new MemoryConfidence(0.7), new MemoryImportance(0.6), content
        );
    }

    private SqliteDialogueJournal open(Path path) { return join(SqliteDialogueJournal.open(path)); }
    private static <T> T join(java.util.concurrent.CompletionStage<T> stage) { return stage.toCompletableFuture().join(); }
}
