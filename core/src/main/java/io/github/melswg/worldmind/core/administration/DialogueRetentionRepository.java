package io.github.melswg.worldmind.core.administration;

import io.github.melswg.worldmind.core.configuration.DialogueRetentionConfiguration;
import java.time.Instant;
import java.util.concurrent.CompletionStage;

/** Bounded storage-owned expiration sweep; never a Minecraft server-thread operation. */
public interface DialogueRetentionRepository {
    CompletionStage<RetentionSweepResult> sweepDialogueRetention(DialogueRetentionConfiguration policy, Instant evaluatedAt);
}
