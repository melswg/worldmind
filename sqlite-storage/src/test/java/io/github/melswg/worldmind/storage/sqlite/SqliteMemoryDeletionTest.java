package io.github.melswg.worldmind.storage.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melswg.worldmind.core.administration.AdministrationResultCode;
import io.github.melswg.worldmind.core.administration.MemoryDeletionRequest;
import io.github.melswg.worldmind.core.administration.MemoryInspectionQuery;
import io.github.melswg.worldmind.core.administration.MemoryInspectionScope;
import io.github.melswg.worldmind.core.administration.MemoryRecordType;
import io.github.melswg.worldmind.core.conversation.AddressingSignal;
import io.github.melswg.worldmind.core.conversation.CapturedPublicChatMessage;
import io.github.melswg.worldmind.core.conversation.ChatBatchSealReason;
import io.github.melswg.worldmind.core.conversation.SealedChatBatch;
import io.github.melswg.worldmind.core.conversation.ServerRequester;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteMemoryDeletionTest {
    @TempDir java.nio.file.Path temporaryDirectory;

    @Test
    void deletesCanonicalPlayerPayloadAndInvalidatesMixedBatchWithoutTouchingOtherRawObservation() {
        ServerRequester target = new ServerRequester(UUID.fromString("d1f640cc-875b-4ddb-908a-4eefc3e0bfe1"), "same-name");
        ServerRequester other = new ServerRequester(UUID.fromString("a011655a-0d5e-4c1f-af7a-41a2dd7ff5cb"), "same-name");
        SqliteDialogueJournal journal = join(SqliteDialogueJournal.open(temporaryDirectory.resolve("world/worldmind/worldmind.sqlite3")));
        var first = join(journal.appendObservation(new CapturedPublicChatMessage(target, "target secret-like content", AddressingSignal.NONE, Instant.EPOCH, List.of())));
        var second = join(journal.appendObservation(new CapturedPublicChatMessage(other, "other public content", AddressingSignal.NONE, Instant.EPOCH, List.of())));
        join(journal.appendBatch(new SealedChatBatch(journal.openedWorldIdentity(), List.of(
            first.toObservedPublicChatMessage(List.of()), second.toObservedPublicChatMessage(List.of())
        ), ChatBatchSealReason.ADDRESSING_SIGNAL, List.of())));

        var request = MemoryDeletionRequest.player(target.playerId());
        var preview = join(journal.prepareDeletion(request));
        assertEquals(AdministrationResultCode.SUCCESS, preview.code());
        var result = join(journal.executeDeletion(request, preview.targetFingerprint().orElseThrow()));
        assertEquals(AdministrationResultCode.SUCCESS, result.code());
        assertFalse(join(journal.inspect(new MemoryInspectionQuery(MemoryInspectionScope.player(target.playerId()), MemoryRecordType.OBSERVATION, Optional.empty()))).records().stream()
            .anyMatch(value -> value.text().contains("target")));
        assertTrue(join(journal.inspect(new MemoryInspectionQuery(MemoryInspectionScope.player(other.playerId()), MemoryRecordType.OBSERVATION, Optional.empty()))).records().stream()
            .anyMatch(value -> value.text().contains("other public content")));
        assertFalse(join(journal.readSnapshot()).observations().stream().anyMatch(value -> value.text().contains("target")));
        join(journal.closeAsync());
    }

    @Test
    void targetFingerprintPreventsAChangedTargetFromBeingDeleted() {
        ServerRequester player = new ServerRequester(UUID.fromString("6bf9763c-099b-4374-8a92-cbf9b6f44674"), "Mira");
        SqliteDialogueJournal journal = join(SqliteDialogueJournal.open(temporaryDirectory.resolve("changed/worldmind/worldmind.sqlite3")));
        var observation = join(journal.appendObservation(new CapturedPublicChatMessage(player, "keep", AddressingSignal.NONE, Instant.EPOCH, List.of())));
        var request = MemoryDeletionRequest.record(MemoryInspectionScope.world(), MemoryRecordType.OBSERVATION, "observation:" + observation.sequence());
        var preview = join(journal.prepareDeletion(request));
        join(journal.appendObservation(new CapturedPublicChatMessage(player, "unrelated", AddressingSignal.NONE, Instant.EPOCH, List.of())));
        assertEquals(AdministrationResultCode.SUCCESS, join(journal.executeDeletion(request, preview.targetFingerprint().orElseThrow())).code());
        assertFalse(join(journal.readSnapshot()).observations().stream().anyMatch(value -> value.text().equals("keep")));
        join(journal.closeAsync());
    }

    private static <T> T join(java.util.concurrent.CompletionStage<T> stage) { return stage.toCompletableFuture().join(); }
}
