package io.github.melswg.worldmind.core.journal;

import io.github.melswg.worldmind.core.conversation.CapturedPublicChatMessage;
import io.github.melswg.worldmind.core.conversation.SealedChatBatch;
import io.github.melswg.worldmind.core.conversation.WorldIdentity;
import io.github.melswg.worldmind.core.configuration.DialogueRetentionConfiguration;
import java.util.concurrent.CompletionStage;

/** Asynchronous world-owned append journal; its callers never touch JDBC or storage credentials. */
public interface DialogueJournal {
    CompletionStage<WorldIdentity> worldIdentity();
    CompletionStage<JournaledObservation> appendObservation(CapturedPublicChatMessage observation);
    default CompletionStage<JournaledObservation> appendObservation(
        CapturedPublicChatMessage observation, DialogueRetentionConfiguration retention
    ) { return appendObservation(observation); }
    CompletionStage<JournaledBatch> appendBatch(SealedChatBatch batch);
    CompletionStage<Void> appendOutcome(JournalBatchOutcome outcome);
    CompletionStage<DialogueJournalSnapshot> readSnapshot();
    CompletionStage<Void> closeAsync();
}
