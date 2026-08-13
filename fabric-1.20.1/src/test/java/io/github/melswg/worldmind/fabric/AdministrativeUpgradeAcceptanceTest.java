package io.github.melswg.worldmind.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melswg.worldmind.core.administration.AdministrationResultCode;
import io.github.melswg.worldmind.core.administration.MemoryDeletionRequest;
import io.github.melswg.worldmind.core.administration.MemoryExportResult;
import io.github.melswg.worldmind.core.administration.MemoryInspectionScope;
import io.github.melswg.worldmind.core.configuration.DialogueRetentionConfiguration;
import io.github.melswg.worldmind.core.configuration.EnabledWorldmindIntegration;
import io.github.melswg.worldmind.core.conversation.AddressingSignal;
import io.github.melswg.worldmind.core.conversation.CapturedPublicChatMessage;
import io.github.melswg.worldmind.core.conversation.ChatBatchSealReason;
import io.github.melswg.worldmind.core.conversation.SealedChatBatch;
import io.github.melswg.worldmind.core.conversation.ServerRequester;
import io.github.melswg.worldmind.core.journal.JournalBatchOutcome;
import io.github.melswg.worldmind.core.journal.JournalDeliveryReport;
import io.github.melswg.worldmind.core.journal.JournalDeliveryStatus;
import io.github.melswg.worldmind.core.journal.JournalParticipationDecision;
import io.github.melswg.worldmind.core.journal.ProviderAttemptOutcome;
import io.github.melswg.worldmind.fabric.configuration.WorldmindStartupConfigurationLoader;
import io.github.melswg.worldmind.storage.sqlite.SqliteDialogueJournal;
import io.github.melswg.worldmind.testkit.WorldmindTestkit;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** One deterministic populated-world administrative upgrade path; no network or Minecraft client is involved. */
class AdministrativeUpgradeAcceptanceTest {
    @TempDir Path temporaryDirectory;

    @Test
    void upgradesConfigAndStorageThenExpiresDeletesExportsAndResetsWithoutLeakingPayloadAfterRestart() throws Exception {
        Path config = temporaryDirectory.resolve("config/worldmind");
        writeV1Configuration(config);
        WorldmindStartupConfigurationLoader loader = new WorldmindStartupConfigurationLoader(config, WorldmindTestkit.secretResolver());
        assertInstanceOf(EnabledWorldmindIntegration.class, loader.loadAndMigrate());
        assertTrue(Files.readString(config.resolve("worldmind.json")).contains("\"schemaVersion\":3"));
        assertTrue(Files.list(config.resolve("backups/config")).anyMatch(Files::isDirectory));

        Path save = temporaryDirectory.resolve("save");
        Path database = WorldmindFabricServerLifecycle.journalDatabasePath(save);
        Files.createDirectories(database.getParent());
        UUID legacyWorld = UUID.fromString("be06f5d7-88ae-48cf-8dc7-72801a9ceef4");
        createPopulatedV1Fixture(database, legacyWorld);
        SqliteDialogueJournal journal = join(SqliteDialogueJournal.open(database));
        assertEquals(2, journal.openedSchemaVersion());
        assertEquals("world-" + legacyWorld, journal.openedWorldIdentity().stableId());

        ServerRequester firstPlayer = new ServerRequester(UUID.fromString("dd0f1d00-2ec5-4bc6-b17e-7db726565936"), "changed-name");
        ServerRequester sameDisplayNameOtherUuid = new ServerRequester(UUID.fromString("c9369b99-2a24-4909-a067-6c06a59ca760"), "changed-name");
        var first = join(journal.appendObservation(new CapturedPublicChatMessage(firstPlayer, "expiring dialogue", AddressingSignal.NONE, Instant.EPOCH, List.of())));
        var second = join(journal.appendObservation(new CapturedPublicChatMessage(sameDisplayNameOtherUuid, "other survives", AddressingSignal.NONE, Instant.EPOCH, List.of())));
        var mixed = join(journal.appendBatch(new SealedChatBatch(journal.openedWorldIdentity(), List.of(
            first.toObservedPublicChatMessage(List.of()), second.toObservedPublicChatMessage(List.of())
        ), ChatBatchSealReason.ADDRESSING_SIGNAL, List.of())));
        join(journal.appendOutcome(new JournalBatchOutcome(mixed.batchId(), ProviderAttemptOutcome.SUCCEEDED,
            Optional.of(JournalParticipationDecision.DIRECT_REPLY), Optional.empty(),
            new JournalDeliveryReport(JournalDeliveryStatus.PUBLIC_REPLY_DELIVERED, Optional.of("fake-provider direct reply")), Instant.EPOCH)));

        var policy = new DialogueRetentionConfiguration(true, 1, false, false, false);
        assertEquals(AdministrationResultCode.SUCCESS, join(journal.sweepDialogueRetention(policy, Instant.EPOCH.plusSeconds(86_400))).code());
        assertFalse(join(journal.readSnapshot()).observations().stream().anyMatch(value -> value.text().contains("expiring dialogue")));

        var preview = join(journal.prepareDeletion(MemoryDeletionRequest.player(firstPlayer.playerId())));
        assertEquals(AdministrationResultCode.SUCCESS, preview.code());
        assertEquals(AdministrationResultCode.SUCCESS, join(journal.executeDeletion(MemoryDeletionRequest.player(firstPlayer.playerId()),
            preview.targetFingerprint().orElseThrow())).code());

        WorldmindMemoryExportPublisher publisher = new WorldmindMemoryExportPublisher(Executors.newSingleThreadExecutor(), Clock.systemUTC());
        MemoryExportResult export = join(publisher.export(journal, save, journal.openedWorldIdentity().stableId(), MemoryInspectionScope.world()));
        assertEquals(AdministrationResultCode.SUCCESS, export.code());
        Path artifact = save.resolve(export.relativeArtifact().orElseThrow());
        assertTrue(WorldmindMemoryExportV1Parser.validate(Files.readString(artifact, StandardCharsets.UTF_8)) >= 0);
        assertFalse(Files.readString(artifact, StandardCharsets.UTF_8).contains("expiring dialogue"));

        var reset = MemoryDeletionRequest.worldReset();
        var resetPreview = join(journal.prepareDeletion(reset));
        assertEquals(AdministrationResultCode.SUCCESS, join(journal.executeDeletion(reset, resetPreview.targetFingerprint().orElseThrow())).code());
        join(journal.closeAsync());

        SqliteDialogueJournal restarted = join(SqliteDialogueJournal.open(database));
        assertEquals("world-" + legacyWorld, restarted.openedWorldIdentity().stableId());
        assertTrue(join(restarted.readSnapshot()).observations().isEmpty());
        assertFalse(new String(Files.readAllBytes(database), StandardCharsets.ISO_8859_1).contains("expiring dialogue"));
        join(restarted.closeAsync());
    }

    private static void writeV1Configuration(Path root) throws Exception {
        Path profile = root.resolve("profiles/oracle");
        Files.createDirectories(profile.resolve("lore"));
        Files.writeString(profile.resolve("persona.md"), "A calm guide.");
        Files.writeString(profile.resolve("rules.md"), "Stay safe.");
        Files.writeString(profile.resolve("lore/world.md"), "A test world.");
        Files.writeString(profile.resolve("profile.json"), """
            {"schemaVersion":1,"characterName":"Aster","personaFile":"persona.md","administratorRulesFile":"rules.md","loreFiles":["lore/world.md"],"responseStyle":"calm","responseLengthLimit":120}
            """);
        Files.createDirectories(root);
        Files.writeString(root.resolve("worldmind.json"), """
            {"schemaVersion":1,"enabled":true,"activeProfile":"oracle","chatBatching":{"maxMessages":8,"maxWaitMillis":5000,"maxEstimatedInputCharacters":4000},"requestQueue":{"capacity":16,"maxConcurrency":2},"provider":{"id":"custom-openai-compatible","endpoint":"https://api.example.invalid/v1/chat/completions","model":"fixture","secretReference":"env:WORLDMIND_TEST_KEY","timeouts":{"connectMillis":5000,"responseCompletionMillis":30000},"retry":{"maximumAttempts":3,"initialBackoffMillis":250,"maximumBackoffMillis":4000,"jitterRatio":0.2},"circuitBreaker":{"failureThreshold":5,"cooldownMillis":30000},"generation":{}}}
            """);
    }

    private static void createPopulatedV1Fixture(Path database, UUID worldId) throws Exception {
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
        }
    }

    private static <T> T join(java.util.concurrent.CompletionStage<T> stage) { return stage.toCompletableFuture().join(); }
}
