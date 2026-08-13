package io.github.melswg.worldmind.storage.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melswg.worldmind.core.conversation.AddressingSignal;
import io.github.melswg.worldmind.core.conversation.CapturedPublicChatMessage;
import io.github.melswg.worldmind.core.conversation.ChatBatchSealReason;
import io.github.melswg.worldmind.core.conversation.ObservedPublicChatMessage;
import io.github.melswg.worldmind.core.conversation.SealedChatBatch;
import io.github.melswg.worldmind.core.conversation.ServerRequester;
import io.github.melswg.worldmind.core.journal.JournalBatchOutcome;
import io.github.melswg.worldmind.core.journal.JournalDeliveryReport;
import io.github.melswg.worldmind.core.journal.JournalParticipationDecision;
import io.github.melswg.worldmind.core.journal.JournaledBatch;
import io.github.melswg.worldmind.core.journal.ProviderAttemptOutcome;
import io.github.melswg.worldmind.core.memory.DerivedMemoryCandidate;
import io.github.melswg.worldmind.core.memory.JournalSequenceRange;
import io.github.melswg.worldmind.core.memory.MemoryCompactionResult;
import io.github.melswg.worldmind.core.memory.MemoryConfirmationAuthority;
import io.github.melswg.worldmind.core.memory.MemoryConfirmationRequest;
import io.github.melswg.worldmind.core.memory.MemoryConfidence;
import io.github.melswg.worldmind.core.memory.MemoryImportance;
import io.github.melswg.worldmind.core.memory.MemoryRetrievalRequest;
import io.github.melswg.worldmind.core.memory.MemoryScope;
import io.github.melswg.worldmind.core.memory.MemoryVisibility;
import io.github.melswg.worldmind.core.memory.ProposedFactCandidate;
import io.github.melswg.worldmind.core.memory.RetrievedMemoryContext;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteMemoryRetrievalTest {
    private static final ServerRequester MIRA = new ServerRequester(
        UUID.fromString("6eac0fe2-1ce7-47fc-9fb7-cd98778b467a"), "Mira"
    );
    private static final ServerRequester OTHER = new ServerRequester(
        UUID.fromString("2d186ebe-224e-483c-af64-e75ccae282c9"), "Other"
    );

    @TempDir Path temporaryDirectory;

    @Test
    void rebuildsFtsAfterRestartAndReturnsOnlyRelevantAllowedTypedMemoryWithinDeterministicBounds() {
        Path database = temporaryDirectory.resolve("primary/worldmind.sqlite3");
        SqliteDialogueJournal journal = open(database);
        List<JournaledBatch> batches = appendCompletedBatches(journal, 32);
        JournaledBatch source = batches.get(0);
        var compactable = join(journal.nextCompaction(journal.openedWorldIdentity())).orElseThrow();
        join(journal.persistCompaction(compactable, new MemoryCompactionResult(
            List.of(candidate("ECLIPSE_EVENT_PUBLIC")), Optional.of(candidate("ECLIPSE_SUMMARY_PUBLIC")),
            Optional.of(candidate("ECLIPSE_CURRENT_PUBLIC"))
        )));

        var proposed = join(journal.appendProposed(source, List.of(
            fact(MemoryScope.world(), MemoryVisibility.PUBLIC, "ECLIPSE_FACT_PUBLIC midnight"),
            fact(MemoryScope.player(MIRA.playerId()), MemoryVisibility.PUBLIC, "MIRA_OBSERVATORY_PUBLIC eclipse"),
            fact(MemoryScope.player(MIRA.playerId()), MemoryVisibility.PRIVATE, "PRIVATE_SHOULD_NOT_LEAK eclipse"),
            fact(MemoryScope.player(OTHER.playerId()), MemoryVisibility.PUBLIC, "OTHER_PLAYER_SHOULD_NOT_LEAK eclipse"),
            fact(MemoryScope.world(), MemoryVisibility.PUBLIC, "IRRELEVANT_SHOULD_NOT_LEAK carrots"),
            fact(MemoryScope.world(), MemoryVisibility.PUBLIC, "PROPOSED_SHOULD_NOT_LEAK eclipse")
        )));
        proposed.subList(0, 5).forEach(record -> join(journal.confirm(record.id(), new MemoryConfirmationRequest(
            MemoryConfirmationAuthority.DETERMINISTIC_POLICY, "retrieval-test"
        ))));
        join(journal.closeAsync());

        SqliteDialogueJournal reopened = open(database);
        RetrievedMemoryContext first = retrieve(reopened, MIRA, "eclipse observatory midnight", 33);
        RetrievedMemoryContext second = retrieve(reopened, MIRA, "eclipse observatory midnight", 33);
        assertEquals(first, second, "ranking and FTS rebuild are deterministic");
        assertTrue(first.currentSituations().stream().anyMatch(entry -> entry.content().contains("ECLIPSE_CURRENT_PUBLIC")));
        String visible = first.entriesInPromptOrder().stream().map(entry -> entry.content()).collect(java.util.stream.Collectors.joining("\n"));
        assertTrue(visible.contains("ECLIPSE_EVENT_PUBLIC") || visible.contains("ECLIPSE_SUMMARY_PUBLIC"));
        assertFalse(visible.contains("PRIVATE_SHOULD_NOT_LEAK"));
        assertFalse(visible.contains("OTHER_PLAYER_SHOULD_NOT_LEAK"));
        assertFalse(visible.contains("IRRELEVANT_SHOULD_NOT_LEAK"));
        assertFalse(visible.contains("PROPOSED_SHOULD_NOT_LEAK"));
        assertTrue(first.entriesInPromptOrder().stream().allMatch(entry -> entry.visibility() == MemoryVisibility.PUBLIC));
        assertTrue(first.entriesInPromptOrder().stream().allMatch(entry -> entry.scope() instanceof MemoryScope.World
            || ((MemoryScope.Player) entry.scope()).playerId().equals(MIRA.playerId())));
        assertTrue(first.entriesInPromptOrder().stream().allMatch(entry -> entry.content().codePointCount(0, entry.content().length()) <= 600));
        assertTrue(first.recentDialogue().size() <= 12 && first.currentSituations().size() <= 4 && first.olderRecords().size() <= 12);
        join(reopened.closeAsync());

        SqliteDialogueJournal otherWorld = open(temporaryDirectory.resolve("other/worldmind.sqlite3"));
        appendCompletedBatches(otherWorld, 1);
        assertFalse(visible.contains("OTHER_WORLD_SHOULD_NOT_LEAK"));
        join(otherWorld.closeAsync());
    }

    private List<JournaledBatch> appendCompletedBatches(SqliteDialogueJournal journal, int count) {
        java.util.ArrayList<JournaledBatch> result = new java.util.ArrayList<>();
        for (int sequence = 1; sequence <= count; sequence++) {
            String content = sequence == 1 ? "eclipse observatory midnight" : "ambient public dialogue " + sequence;
            var observation = join(journal.appendObservation(new CapturedPublicChatMessage(
                MIRA, content, AddressingSignal.NONE, Instant.ofEpochSecond(sequence), List.of()
            )));
            JournaledBatch batch = join(journal.appendBatch(new SealedChatBatch(
                journal.openedWorldIdentity(), List.of(observation.toObservedPublicChatMessage(List.of())),
                ChatBatchSealReason.MAXIMUM_MESSAGE_COUNT, List.of()
            )));
            Optional<JournalParticipationDecision> decision = sequence % 2 == 0
                ? Optional.of(JournalParticipationDecision.SILENT) : Optional.of(JournalParticipationDecision.AMBIENT_REPLY);
            join(journal.appendOutcome(new JournalBatchOutcome(batch.batchId(), ProviderAttemptOutcome.SUCCEEDED, decision,
                Optional.empty(), JournalDeliveryReport.noOutput(), Instant.ofEpochSecond(sequence))));
            result.add(batch);
        }
        return List.copyOf(result);
    }

    private RetrievedMemoryContext retrieve(SqliteDialogueJournal journal, ServerRequester requester, String text, long sequence) {
        return join(journal.retrievePublic(new MemoryRetrievalRequest(new SealedChatBatch(
            journal.openedWorldIdentity(), List.of(new ObservedPublicChatMessage(
                sequence, requester, text, AddressingSignal.EXACT, Instant.ofEpochSecond(sequence), List.of()
            )), ChatBatchSealReason.ADDRESSING_SIGNAL, List.of()
        ))));
    }

    private ProposedFactCandidate fact(MemoryScope scope, MemoryVisibility visibility, String content) {
        return new ProposedFactCandidate(scope, visibility, new JournalSequenceRange(1, 1),
            new MemoryConfidence(0.8), new MemoryImportance(0.8), content);
    }

    private DerivedMemoryCandidate candidate(String content) {
        return new DerivedMemoryCandidate(MemoryScope.world(), MemoryVisibility.PUBLIC,
            new MemoryConfidence(0.7), new MemoryImportance(0.7), content);
    }

    private SqliteDialogueJournal open(Path path) { return join(SqliteDialogueJournal.open(path)); }
    private static <T> T join(java.util.concurrent.CompletionStage<T> stage) { return stage.toCompletableFuture().join(); }
}
