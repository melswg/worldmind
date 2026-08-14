package io.github.melswg.worldmind.fabric;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melswg.worldmind.core.administration.ConfigurationValidationReport;
import io.github.melswg.worldmind.core.administration.MemoryExportResult;
import io.github.melswg.worldmind.core.administration.MemoryInspectionScope;
import io.github.melswg.worldmind.core.configuration.ConfigurationDiagnostic;
import io.github.melswg.worldmind.core.configuration.DisabledWorldmindIntegration;
import io.github.melswg.worldmind.core.configuration.IntegrationDisableReason;
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

class RedactionStorageCanaryTest {
    @TempDir Path save;

    @Test
    void registeredCanaryIsAbsentFromSqliteExportAndSafeDiagnostics() throws Exception {
        String canary = "wm-canary-" + UUID.randomUUID();
        SecretRedactionPolicy.register(canary);
        ServerRequester requester = new ServerRequester(UUID.fromString("6eac0fe2-1ce7-47fc-9fb7-cd98778b467a"), "Mira");
        Path database = save.resolve("worldmind/worldmind.sqlite3");
        SqliteDialogueJournal journal = join(SqliteDialogueJournal.open(database));
        var observation = join(journal.appendObservation(new CapturedPublicChatMessage(
            requester, "unsafe observation " + canary, AddressingSignal.EXACT, Instant.EPOCH, List.of()
        )));
        var batch = join(journal.appendBatch(new SealedChatBatch(journal.openedWorldIdentity(), List.of(
            observation.toObservedPublicChatMessage(List.of())
        ), ChatBatchSealReason.ADDRESSING_SIGNAL, List.of())));
        join(journal.appendOutcome(new JournalBatchOutcome(batch.batchId(), ProviderAttemptOutcome.SUCCEEDED,
            Optional.of(JournalParticipationDecision.DIRECT_REPLY), Optional.empty(),
            new JournalDeliveryReport(JournalDeliveryStatus.PUBLIC_REPLY_DELIVERED, Optional.of("unsafe reply " + canary)), Instant.EPOCH)));

        ExecutorService exporterExecutor = Executors.newSingleThreadExecutor();
        try {
            WorldmindMemoryExportPublisher exporter = new WorldmindMemoryExportPublisher(
                exporterExecutor, Clock.fixed(Instant.EPOCH, java.time.ZoneOffset.UTC)
            );
            MemoryExportResult result = join(exporter.export(journal, save, journal.openedWorldIdentity().stableId(), MemoryInspectionScope.world()));
            Path export = save.resolve(result.relativeArtifact().orElseThrow());
            String exportText = Files.readString(export, StandardCharsets.UTF_8);
            assertTrue(exportText.contains("[REDACTED]"));
            assertFalse(exportText.contains(canary));
        } finally {
            exporterExecutor.shutdownNow();
            join(journal.closeAsync());
        }

        assertNoCanaryInRegularFiles(save, canary);
        ConfigurationValidationReport diagnostic = ConfigurationValidationReport.fromIntegrationState(
            new DisabledWorldmindIntegration(IntegrationDisableReason.SECRET_UNREADABLE,
                List.of(new ConfigurationDiagnostic("global.provider.secretReference", "Credential " + canary + " unavailable")))
        );
        assertFalse(diagnostic.diagnostics().toString().contains(canary));
        assertTrue(diagnostic.diagnostics().toString().contains("credential material is unavailable"));
    }

    private static void assertNoCanaryInRegularFiles(Path root, String canary) throws Exception {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String content = new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1);
                assertFalse(content.contains(canary), () -> "Canary leaked into " + path.getFileName());
            }
        }
    }

    private static <T> T join(java.util.concurrent.CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }
}
