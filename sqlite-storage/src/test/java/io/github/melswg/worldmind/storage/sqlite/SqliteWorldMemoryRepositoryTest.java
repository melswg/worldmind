package io.github.melswg.worldmind.storage.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melswg.worldmind.core.conversation.AddressingSignal;
import io.github.melswg.worldmind.core.conversation.CapturedPublicChatMessage;
import io.github.melswg.worldmind.core.conversation.ChatBatchSealReason;
import io.github.melswg.worldmind.core.conversation.SealedChatBatch;
import io.github.melswg.worldmind.core.conversation.ServerRequester;
import io.github.melswg.worldmind.core.journal.JournaledBatch;
import io.github.melswg.worldmind.core.journal.JournaledObservation;
import io.github.melswg.worldmind.core.memory.JournalSequenceRange;
import io.github.melswg.worldmind.core.memory.MemoryConfirmationAuthority;
import io.github.melswg.worldmind.core.memory.MemoryConfirmationRequest;
import io.github.melswg.worldmind.core.memory.MemoryConfidence;
import io.github.melswg.worldmind.core.memory.MemoryFact;
import io.github.melswg.worldmind.core.memory.MemoryImportance;
import io.github.melswg.worldmind.core.memory.MemoryRecord;
import io.github.melswg.worldmind.core.memory.MemoryRetrievalRequest;
import io.github.melswg.worldmind.core.memory.RetrievedMemoryEntry;
import io.github.melswg.worldmind.core.memory.MemoryRecordState;
import io.github.melswg.worldmind.core.memory.MemoryScope;
import io.github.melswg.worldmind.core.memory.MemoryVisibility;
import io.github.melswg.worldmind.core.memory.ProposedFactCandidate;
import io.github.melswg.worldmind.core.memory.ProposedMemoryCandidate;
import io.github.melswg.worldmind.core.memory.ProposedRelationshipCandidate;
import io.github.melswg.worldmind.core.memory.RelationshipMemory;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteWorldMemoryRepositoryTest {
    private static final ServerRequester MIRA = new ServerRequester(
        UUID.fromString("6eac0fe2-1ce7-47fc-9fb7-cd98778b467a"), "Mira"
    );
    private static final ServerRequester RENAMED_MIRA = new ServerRequester(MIRA.playerId(), "CinderMira");
    private static final ServerRequester OTHER = new ServerRequester(
        UUID.fromString("2d186ebe-224e-483c-af64-e75ccae282c9"), "Mira"
    );

    @TempDir Path temporaryDirectory;

    @Test
    void persistsAuditableCandidatesAndRecallsOnlyConfirmedPublicWorldAndParticipantRecordsAfterRestart() {
        Path database = temporaryDirectory.resolve("one/worldmind/worldmind.sqlite3");
        SqliteDialogueJournal first = open(database);
        JournaledBatch source = sealed(first, MIRA, "The observatory is east.", "Aster!");

        List<MemoryRecord> proposed = join(first.appendProposed(source, List.of(
            fact(MemoryScope.world(), MemoryVisibility.PUBLIC, 1, 1, "The observatory is east."),
            fact(MemoryScope.player(MIRA.playerId()), MemoryVisibility.PUBLIC, 2, 2, "Mira carries the map."),
            relationship(MemoryScope.player(MIRA.playerId()), MemoryVisibility.PUBLIC, MIRA.playerId(), 2, 2, "trusted scout"),
            fact(MemoryScope.player(MIRA.playerId()), MemoryVisibility.PRIVATE, 2, 2, "Mira shared a private worry."),
            relationship(MemoryScope.player(MIRA.playerId()), MemoryVisibility.PRIVATE, MIRA.playerId(), 2, 2,
                "Mira privately distrusts the merchant."),
            fact(MemoryScope.player(OTHER.playerId()), MemoryVisibility.PUBLIC, 1, 1, "Other Mira built the bridge."),
            fact(MemoryScope.world(), MemoryVisibility.PUBLIC, 1, 2, "This remains proposed.")
        )));
        proposed.subList(0, 6).forEach(record -> join(first.confirm(
            record.id(), new MemoryConfirmationRequest(MemoryConfirmationAuthority.DETERMINISTIC_POLICY, "ticket-12-test")
        )));
        MemoryRecord repeated = join(first.confirm(
            proposed.get(0).id(), new MemoryConfirmationRequest(MemoryConfirmationAuthority.AUTHORIZED_OPERATOR, "must-not-replace")
        ));
        assertEquals(MemoryConfirmationAuthority.DETERMINISTIC_POLICY, repeated.confirmation().orElseThrow().authority());
        assertEquals(7, join(first.readMemorySnapshot()).records().size());
        join(first.closeAsync());

        SqliteDialogueJournal reopened = open(database);
        List<RetrievedMemoryEntry> recalled = join(reopened.retrievePublic(new MemoryRetrievalRequest(
            nextBatch(reopened, RENAMED_MIRA, 3, "observatory Mira trusted")
        ))).olderRecords();
        List<RetrievedMemoryEntry> recalledRecords = recalled.stream()
            .filter(record -> record.type() != io.github.melswg.worldmind.core.memory.RetrievedMemoryRecordType.DIALOGUE).toList();
        assertEquals(3, recalledRecords.size());
        assertEquals(
            java.util.Set.of("The observatory is east.", "Mira carries the map.", "trusted scout"),
            new java.util.HashSet<>(recalledRecords.stream().map(this::content).toList())
        );
        assertTrue(recalled.stream().allMatch(record -> record.visibility() == MemoryVisibility.PUBLIC));

        List<RetrievedMemoryEntry> differentUuid = join(reopened.retrievePublic(new MemoryRetrievalRequest(
            nextBatch(reopened, OTHER, 3, "observatory bridge")
        ))).olderRecords();
        assertEquals(java.util.Set.of("The observatory is east.", "Other Mira built the bridge."),
            new java.util.HashSet<>(differentUuid.stream()
                .filter(record -> record.type() != io.github.melswg.worldmind.core.memory.RetrievedMemoryRecordType.DIALOGUE)
                .map(this::content).toList()));

        List<MemoryRecord> snapshot = join(reopened.readMemorySnapshot()).records();
        assertEquals(7, snapshot.size());
        MemoryFact proposedFact = assertInstanceOf(MemoryFact.class, snapshot.stream()
            .filter(record -> record instanceof MemoryFact fact && fact.content().equals("This remains proposed."))
            .findFirst().orElseThrow());
        assertEquals(MemoryRecordState.PROPOSED, proposedFact.state());
        assertTrue(proposedFact.confirmation().isEmpty());
        assertFalse(new String(readBytes(database)).contains("WORLDMIND_ACCEPTANCE_KEY"));
        join(reopened.closeAsync());
    }

    @Test
    void rollsBackCandidateSetWhenOneRangeIsNotAnExactContiguousMemberRangeAndExcludesCurrentSource() {
        SqliteDialogueJournal journal = open(temporaryDirectory.resolve("rollback/worldmind/worldmind.sqlite3"));
        JournaledBatch source = sealed(journal, MIRA, "one", "two");
        List<ProposedMemoryCandidate> invalidSet = List.of(
            fact(MemoryScope.world(), MemoryVisibility.PUBLIC, 1, 1, "valid but rolled back"),
            fact(MemoryScope.world(), MemoryVisibility.PUBLIC, 1, 3, "invalid range")
        );
        assertThrows(CompletionException.class, () -> join(journal.appendProposed(source, invalidSet)));
        assertTrue(join(journal.readMemorySnapshot()).records().isEmpty());

        MemoryRecord confirmed = join(journal.appendProposed(source, List.of(
            fact(MemoryScope.world(), MemoryVisibility.PUBLIC, 1, 2, "only prior batches are visible")
        ))).get(0);
        join(journal.confirm(confirmed.id(), new MemoryConfirmationRequest(
            MemoryConfirmationAuthority.DETERMINISTIC_POLICY, "test"
        )));
        assertTrue(join(journal.retrievePublic(new MemoryRetrievalRequest(nextBatch(journal, MIRA, 2, "prior")))).olderRecords().isEmpty());
        assertEquals(1, join(journal.retrievePublic(new MemoryRetrievalRequest(nextBatch(journal, MIRA, 3, "prior")))).olderRecords().size());
        join(journal.closeAsync());
    }

    @Test
    void isolatesSaveDirectoriesAndRejectsCandidateBatchesFromAnotherWorld() {
        SqliteDialogueJournal first = open(temporaryDirectory.resolve("first/worldmind/worldmind.sqlite3"));
        SqliteDialogueJournal second = open(temporaryDirectory.resolve("second/worldmind/worldmind.sqlite3"));
        JournaledBatch firstBatch = sealed(first, MIRA, "first", "first exact");
        sealed(second, OTHER, "second", "second exact");
        assertThrows(CompletionException.class, () -> join(second.appendProposed(firstBatch, List.of(
            fact(MemoryScope.world(), MemoryVisibility.PUBLIC, 1, 1, "must fail")
        ))));
        assertTrue(join(second.readMemorySnapshot()).records().isEmpty());
        join(first.closeAsync());
        join(second.closeAsync());
    }

    private JournaledBatch sealed(SqliteDialogueJournal journal, ServerRequester requester, String first, String second) {
        JournaledObservation one = join(journal.appendObservation(message(requester, first, AddressingSignal.NONE)));
        JournaledObservation two = join(journal.appendObservation(message(requester, second, AddressingSignal.EXACT)));
        return join(journal.appendBatch(new SealedChatBatch(
            journal.openedWorldIdentity(),
            List.of(one.toObservedPublicChatMessage(List.of()), two.toObservedPublicChatMessage(List.of())),
            ChatBatchSealReason.ADDRESSING_SIGNAL,
            List.of()
        )));
    }

    private SealedChatBatch nextBatch(SqliteDialogueJournal journal, ServerRequester requester, long firstSequence, String text) {
        return new SealedChatBatch(
            journal.openedWorldIdentity(),
            List.of(new io.github.melswg.worldmind.core.conversation.ObservedPublicChatMessage(
                firstSequence, requester, text, AddressingSignal.EXACT, Instant.EPOCH, List.of()
            )),
            ChatBatchSealReason.ADDRESSING_SIGNAL,
            List.of()
        );
    }

    private ProposedFactCandidate fact(
        MemoryScope scope,
        MemoryVisibility visibility,
        long firstSequence,
        long lastSequence,
        String content
    ) {
        return new ProposedFactCandidate(scope, visibility, new JournalSequenceRange(firstSequence, lastSequence),
            new MemoryConfidence(0.8), new MemoryImportance(0.6), content);
    }

    private ProposedRelationshipCandidate relationship(
        MemoryScope scope,
        MemoryVisibility visibility,
        UUID subject,
        long firstSequence,
        long lastSequence,
        String state
    ) {
        return new ProposedRelationshipCandidate(scope, visibility, new JournalSequenceRange(firstSequence, lastSequence),
            new MemoryConfidence(0.9), new MemoryImportance(0.7), subject, state);
    }

    private CapturedPublicChatMessage message(ServerRequester requester, String text, AddressingSignal signal) {
        return new CapturedPublicChatMessage(requester, text, signal, Instant.EPOCH, List.of());
    }

    private SqliteDialogueJournal open(Path path) {
        return join(SqliteDialogueJournal.open(path));
    }

    private String content(Object record) {
        if (record instanceof RetrievedMemoryEntry entry) return entry.content();
        return record instanceof MemoryFact fact ? fact.content() : ((RelationshipMemory) record).relationshipState();
    }

    private byte[] readBytes(Path path) {
        try {
            return java.nio.file.Files.readAllBytes(path);
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        }
    }

    private static <T> T join(java.util.concurrent.CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }
}
