package io.github.melswg.worldmind.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.melswg.worldmind.core.administration.MemoryExportResult;
import io.github.melswg.worldmind.core.administration.MemoryInspectionScope;
import io.github.melswg.worldmind.core.configuration.SecretRedactionPolicy;
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
import io.github.melswg.worldmind.storage.sqlite.SqliteDialogueJournal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldmindMemoryExportPublisherTest {
    private static final ServerRequester FIRST = new ServerRequester(
        UUID.fromString("6eac0fe2-1ce7-47fc-9fb7-cd98778b467a"), "same-name"
    );
    private static final ServerRequester SECOND = new ServerRequester(
        UUID.fromString("922ec6b5-623e-42a2-a893-d3892615b7e6"), "same-name"
    );

    @TempDir Path save;

    @Test
    void writesAParseableAtomicWorldExportAndPlayerFilterExcludesMixedRecords() throws Exception {
        SecretRedactionPolicy.register("test-export-secret");
        SqliteDialogueJournal journal = join(SqliteDialogueJournal.open(save.resolve("worldmind/worldmind.sqlite3")));
        var first = join(journal.appendObservation(message(FIRST, "only-first test-export-secret")));
        var second = join(journal.appendObservation(message(SECOND, "only-second")));
        var mixed = join(journal.appendBatch(new SealedChatBatch(journal.openedWorldIdentity(), List.of(
            first.toObservedPublicChatMessage(List.of()), second.toObservedPublicChatMessage(List.of())
        ), ChatBatchSealReason.ADDRESSING_SIGNAL, List.of())));
        join(journal.appendOutcome(outcome(mixed, "mixed reply")));
        var third = join(journal.appendObservation(message(FIRST, "exclusive")));
        var exclusive = join(journal.appendBatch(new SealedChatBatch(journal.openedWorldIdentity(), List.of(
            third.toObservedPublicChatMessage(List.of())
        ), ChatBatchSealReason.ADDRESSING_SIGNAL, List.of())));
        join(journal.appendOutcome(outcome(exclusive, "exclusive reply")));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            WorldmindMemoryExportPublisher publisher = new WorldmindMemoryExportPublisher(
                executor, Clock.fixed(Instant.parse("2026-08-13T10:15:30Z"), java.time.ZoneOffset.UTC)
            );
            MemoryExportResult player = join(publisher.export(journal, save, journal.openedWorldIdentity().stableId(),
                MemoryInspectionScope.player(FIRST.playerId())));
            assertEquals("SUCCESS", player.code().name());
            Path playerArtifact = save.resolve(player.relativeArtifact().orElseThrow());
            assertTrue(Files.exists(playerArtifact));
            String playerJson = Files.readString(playerArtifact, StandardCharsets.UTF_8);
            assertTrue(WorldmindMemoryExportV1Parser.validate(playerJson) >= 2);
            assertTrue(playerJson.contains("exclusive reply"));
            assertFalse(playerJson.contains("mixed reply"));
            assertFalse(playerJson.contains("only-second"));
            assertFalse(playerJson.contains("test-export-secret"));
            assertFalse(playerJson.contains("same-name"));

            MemoryExportResult world = join(publisher.export(journal, save, journal.openedWorldIdentity().stableId(),
                MemoryInspectionScope.world()));
            String worldJson = Files.readString(save.resolve(world.relativeArtifact().orElseThrow()), StandardCharsets.UTF_8);
            assertEquals(9, WorldmindMemoryExportV1Parser.validate(worldJson));
        } finally {
            executor.shutdownNow();
            join(journal.closeAsync());
        }
    }

    @Test
    void parserRejectsAnUnsupportedFormatVersion() {
        assertThrows(IllegalArgumentException.class, () -> WorldmindMemoryExportV1Parser.validate("""
            {"formatName":"worldmind-memory-export","formatVersion":2,"metadata":{},"records":[],"recordCounts":{}}
            """));
    }

    private static CapturedPublicChatMessage message(ServerRequester requester, String text) {
        return new CapturedPublicChatMessage(requester, text, AddressingSignal.NONE, Instant.EPOCH, List.of());
    }

    private static JournalBatchOutcome outcome(io.github.melswg.worldmind.core.journal.JournaledBatch batch, String reply) {
        return new JournalBatchOutcome(batch.batchId(), ProviderAttemptOutcome.SUCCEEDED,
            Optional.of(JournalParticipationDecision.DIRECT_REPLY), Optional.empty(),
            new JournalDeliveryReport(JournalDeliveryStatus.PUBLIC_REPLY_DELIVERED, Optional.of(reply)), Instant.EPOCH);
    }

    private static <T> T join(java.util.concurrent.CompletionStage<T> stage) { return stage.toCompletableFuture().join(); }
}
