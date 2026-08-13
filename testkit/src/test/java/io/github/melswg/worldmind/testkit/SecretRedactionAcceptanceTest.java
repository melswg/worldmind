package io.github.melswg.worldmind.testkit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melswg.worldmind.core.configuration.SecretRedactionPolicy;
import io.github.melswg.worldmind.core.conversation.AddressingSignal;
import io.github.melswg.worldmind.core.conversation.CapturedPublicChatMessage;
import io.github.melswg.worldmind.core.conversation.ServerRequester;
import io.github.melswg.worldmind.core.conversation.WorldIdentity;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SecretRedactionAcceptanceTest {
    @Test
    void canaryNeverAppearsInSafeDiagnosticsOrPersistedJournalSnapshot() {
        String canary = "wm-canary-6b7d9f84-0a90-45d2-8a2e-13c40eb1b217";
        SecretRedactionPolicy.register(canary);
        String unsafe = "player text " + canary + " and more";
        assertFalse(SecretRedactionPolicy.redact(unsafe).contains(canary));
        assertTrue(SecretRedactionPolicy.redact(unsafe).contains("[REDACTED]"));

        InMemoryDialogueJournal journal = new InMemoryDialogueJournal(new WorldIdentity("safe-world"), Clock.systemUTC());
        journal.appendObservation(new CapturedPublicChatMessage(
            new ServerRequester(UUID.fromString("d0ae1d4c-9bcd-4ff9-a16b-12b27a2d00f5"), "Mira"), unsafe,
            AddressingSignal.NONE, Instant.EPOCH, List.of()
        )).toCompletableFuture().join();
        String journalText = journal.readSnapshot().toCompletableFuture().join().observations().get(0).text();
        assertFalse(journalText.contains(canary));
    }
}
