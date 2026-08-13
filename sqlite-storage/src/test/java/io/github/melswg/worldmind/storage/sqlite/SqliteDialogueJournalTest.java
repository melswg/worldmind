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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
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
    void migratesPopulatedV1WithVacuumBackupWithoutChangingIdentityOrSequence() throws Exception {
        Path database = temporaryDirectory.resolve("legacy/worldmind/" + SqliteDialogueJournal.DATABASE_FILE_NAME);
        Files.createDirectories(database.getParent());
        UUID batchId = UUID.fromString("7dc96eea-2a6f-43e2-9c1c-583165d0cc9d");
        UUID legacyWorld = UUID.fromString("f5c3a20e-721b-490c-bb7c-93179a1ce110");
        createV1Fixture(database, legacyWorld, batchId);

        SqliteDialogueJournal migrated = open(database);
        assertEquals(2, migrated.openedSchemaVersion());
        assertEquals("world-" + legacyWorld, migrated.openedWorldIdentity().stableId());
        assertEquals(List.of(1L), join(migrated.readSnapshot()).observations().stream().map(value -> value.sequence()).toList());
        assertEquals(2L, join(migrated.appendObservation(message("after migration", AddressingSignal.NONE))).sequence());
        assertFalse(Files.list(database.getParent().resolve("backups/storage")).findAny().isEmpty());
        join(migrated.closeAsync());
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

    private static void createV1Fixture(Path database, UUID worldId, UUID batchId) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE journal_metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            statement.execute("INSERT INTO journal_metadata VALUES ('schema_version', '1')");
            statement.execute("INSERT INTO journal_metadata VALUES ('world_id', '" + worldId + "')");
            statement.execute("CREATE TABLE journal_messages (sequence INTEGER PRIMARY KEY AUTOINCREMENT, player_uuid TEXT NOT NULL, player_name TEXT NOT NULL, message_text TEXT NOT NULL, captured_at_epoch_millis INTEGER NOT NULL, source TEXT NOT NULL, visibility TEXT NOT NULL, addressing_signal TEXT NOT NULL)");
            statement.execute("CREATE TABLE journal_batches (batch_id TEXT PRIMARY KEY, first_sequence INTEGER NOT NULL, last_sequence INTEGER NOT NULL, seal_reason TEXT NOT NULL, sealed_at_epoch_millis INTEGER NOT NULL)");
            statement.execute("CREATE TABLE journal_batch_messages (batch_id TEXT NOT NULL, message_sequence INTEGER NOT NULL, ordinal INTEGER NOT NULL, PRIMARY KEY(batch_id, message_sequence), UNIQUE(batch_id, ordinal))");
            statement.execute("CREATE TABLE journal_outcomes (batch_id TEXT PRIMARY KEY, provider_attempt_outcome TEXT NOT NULL, decision TEXT, refusal_code TEXT, delivery_status TEXT NOT NULL, delivered_response TEXT, completed_at_epoch_millis INTEGER NOT NULL)");
            statement.execute("CREATE TABLE memory_records (record_id TEXT PRIMARY KEY, record_type TEXT, record_state TEXT, content TEXT, scope_type TEXT, scope_player_uuid TEXT, visibility TEXT, source_batch_id TEXT, first_sequence INTEGER, last_sequence INTEGER, source_timestamp_epoch_millis INTEGER, recorded_at_epoch_millis INTEGER, confidence REAL, importance REAL, relationship_subject_uuid TEXT)");
            statement.execute("CREATE TABLE memory_confirmations (record_id TEXT PRIMARY KEY, authority TEXT, authority_identifier TEXT, confirmed_at_epoch_millis INTEGER)");
            statement.execute("CREATE TABLE memory_events (event_id TEXT PRIMARY KEY, content TEXT, scope_type TEXT, scope_player_uuid TEXT, visibility TEXT, first_sequence INTEGER, last_sequence INTEGER, source_timestamp_epoch_millis INTEGER, recorded_at_epoch_millis INTEGER, confidence REAL, importance REAL)");
            statement.execute("CREATE TABLE memory_summary_versions (summary_version_id TEXT PRIMARY KEY, summary_series_id TEXT, version_number INTEGER, content TEXT, scope_type TEXT, scope_player_uuid TEXT, visibility TEXT, first_sequence INTEGER, last_sequence INTEGER, source_timestamp_epoch_millis INTEGER, recorded_at_epoch_millis INTEGER, confidence REAL, importance REAL)");
            statement.execute("CREATE TABLE memory_current_situation_versions (situation_version_id TEXT PRIMARY KEY, situation_series_id TEXT, version_number INTEGER, content TEXT, scope_type TEXT, scope_player_uuid TEXT, visibility TEXT, first_sequence INTEGER, last_sequence INTEGER, source_timestamp_epoch_millis INTEGER, recorded_at_epoch_millis INTEGER, confidence REAL, importance REAL)");
            statement.execute("CREATE TABLE memory_derived_sources (record_kind TEXT, record_id TEXT, batch_id TEXT, ordinal INTEGER)");
            statement.execute("CREATE TABLE memory_compaction_coverage (first_sequence INTEGER, last_sequence INTEGER, recorded_at_epoch_millis INTEGER)");
            statement.execute("CREATE TABLE memory_search_documents (document_id INTEGER PRIMARY KEY AUTOINCREMENT, record_type TEXT, stable_identity TEXT, content TEXT, scope_type TEXT, scope_player_uuid TEXT, visibility TEXT, first_sequence INTEGER, last_sequence INTEGER, source_timestamp_epoch_millis INTEGER, recorded_at_epoch_millis INTEGER, confidence REAL, importance REAL, source_batch_ids TEXT)");
            statement.execute("CREATE VIRTUAL TABLE memory_search_fts USING fts5(content, content='memory_search_documents', content_rowid='document_id')");
            statement.execute("INSERT INTO journal_messages(player_uuid, player_name, message_text, captured_at_epoch_millis, source, visibility, addressing_signal) VALUES ('" + MIRA.playerId() + "', 'Mira', 'legacy raw', 0, 'PUBLIC_CHAT', 'PUBLIC', 'NONE')");
            statement.execute("INSERT INTO journal_batches VALUES ('" + batchId + "', 1, 1, 'ADDRESSING_SIGNAL', 0)");
            statement.execute("INSERT INTO journal_batch_messages VALUES ('" + batchId + "', 1, 0)");
            statement.execute("INSERT INTO journal_outcomes VALUES ('" + batchId + "', 'SUCCEEDED', 'DIRECT_REPLY', NULL, 'PUBLIC_REPLY_DELIVERED', 'legacy reply', 0)");
        }
    }

    private static <T> T join(java.util.concurrent.CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }
}
