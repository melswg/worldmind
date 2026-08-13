package io.github.melswg.worldmind.storage.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melswg.worldmind.core.administration.AdministrationResultCode;
import io.github.melswg.worldmind.core.configuration.DialogueRetentionConfiguration;
import io.github.melswg.worldmind.core.conversation.AddressingSignal;
import io.github.melswg.worldmind.core.conversation.CapturedPublicChatMessage;
import io.github.melswg.worldmind.core.conversation.ServerRequester;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteDialogueRetentionTest {
    @TempDir java.nio.file.Path temporaryDirectory;

    @Test
    void disabledRawPersistenceKeepsDurableAdmissionButNoPayloadAfterRestart() {
        ServerRequester player = new ServerRequester(UUID.fromString("ee62edf9-bb7d-49f3-b01d-86e7046b87ec"), "Mira");
        var policy = new DialogueRetentionConfiguration(false, 0, false, false, false);
        SqliteDialogueJournal journal = open("disabled");
        assertEquals(1L, join(journal.appendObservation(new CapturedPublicChatMessage(player, "not persisted", AddressingSignal.NONE, Instant.EPOCH, List.of()), policy)).sequence());
        join(journal.closeAsync());

        SqliteDialogueJournal reopened = open("disabled");
        assertTrue(join(reopened.readSnapshot()).observations().isEmpty());
        join(reopened.closeAsync());
    }

    @Test
    void inclusiveUtcCutoffExpiresAtMostOneBoundedPage() {
        ServerRequester player = new ServerRequester(UUID.fromString("8a11e15e-efb9-465b-a7bb-1ce04b65e046"), "Mira");
        SqliteDialogueJournal journal = open("expiry");
        for (int index = 0; index < 257; index++) {
            join(journal.appendObservation(new CapturedPublicChatMessage(player, "raw " + index, AddressingSignal.NONE, Instant.EPOCH, List.of())));
        }
        var policy = new DialogueRetentionConfiguration(true, 1, true, true, true);
        var first = join(journal.sweepDialogueRetention(policy, Instant.EPOCH.plus(java.time.Duration.ofDays(1))));
        assertEquals(AdministrationResultCode.SUCCESS, first.code());
        assertEquals(256, first.expiredObservations());
        assertTrue(first.moreRemaining());
        var second = join(journal.sweepDialogueRetention(policy, Instant.EPOCH.plus(java.time.Duration.ofDays(1))));
        assertEquals(1, second.expiredObservations());
        assertTrue(join(journal.readSnapshot()).observations().isEmpty());
        join(journal.closeAsync());
    }

    private SqliteDialogueJournal open(String name) {
        return join(SqliteDialogueJournal.open(temporaryDirectory.resolve(name + "/worldmind/worldmind.sqlite3")));
    }

    private static <T> T join(java.util.concurrent.CompletionStage<T> stage) { return stage.toCompletableFuture().join(); }
}
