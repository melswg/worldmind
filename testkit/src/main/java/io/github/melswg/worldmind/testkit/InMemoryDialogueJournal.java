package io.github.melswg.worldmind.testkit;

import io.github.melswg.worldmind.core.conversation.CapturedPublicChatMessage;
import io.github.melswg.worldmind.core.conversation.SealedChatBatch;
import io.github.melswg.worldmind.core.conversation.WorldIdentity;
import io.github.melswg.worldmind.core.journal.DialogueJournal;
import io.github.melswg.worldmind.core.journal.DialogueJournalSnapshot;
import io.github.melswg.worldmind.core.journal.JournalBatchOutcome;
import io.github.melswg.worldmind.core.journal.JournalMessageSource;
import io.github.melswg.worldmind.core.journal.JournalVisibility;
import io.github.melswg.worldmind.core.journal.JournaledBatch;
import io.github.melswg.worldmind.core.journal.JournaledObservation;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Deterministic, non-JDBC journal used by adapter tests; production always uses SQLite. */
public final class InMemoryDialogueJournal implements DialogueJournal {
    private final WorldIdentity worldIdentity;
    private final Clock clock;
    private final List<JournaledObservation> observations = new ArrayList<>();
    private final List<JournaledBatch> batches = new ArrayList<>();
    private final Map<java.util.UUID, JournalBatchOutcome> outcomes = new LinkedHashMap<>();
    private boolean closed;

    public InMemoryDialogueJournal(WorldIdentity worldIdentity, Clock clock) {
        this.worldIdentity = java.util.Objects.requireNonNull(worldIdentity, "worldIdentity");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    @Override public synchronized CompletionStage<WorldIdentity> worldIdentity() { return available(worldIdentity); }

    @Override public synchronized CompletionStage<JournaledObservation> appendObservation(CapturedPublicChatMessage observation) {
        if (closed) return failed(new IllegalStateException("Dialogue journal is closed."));
        long sequence = observations.size() + 1L;
        JournaledObservation journaled = new JournaledObservation(
            worldIdentity, sequence, observation.requester(), observation.message(), observation.capturedAt(),
            JournalMessageSource.PUBLIC_CHAT, JournalVisibility.PUBLIC, observation.addressingSignal()
        );
        observations.add(journaled);
        return available(journaled);
    }

    @Override public synchronized CompletionStage<JournaledBatch> appendBatch(SealedChatBatch batch) {
        if (closed) return failed(new IllegalStateException("Dialogue journal is closed."));
        if (!worldIdentity.equals(batch.worldIdentity())) return failed(new IllegalArgumentException("Mismatched journal world."));
        JournaledBatch journaled = JournaledBatch.from(batch, clock.instant());
        batches.add(journaled);
        return available(journaled);
    }

    @Override public synchronized CompletionStage<Void> appendOutcome(JournalBatchOutcome outcome) {
        if (closed) return failed(new IllegalStateException("Dialogue journal is closed."));
        outcomes.put(outcome.batchId(), outcome);
        return available(null);
    }

    @Override public synchronized CompletionStage<DialogueJournalSnapshot> readSnapshot() {
        return available(new DialogueJournalSnapshot(worldIdentity, observations, batches, outcomes));
    }

    @Override public synchronized CompletionStage<Void> closeAsync() {
        closed = true;
        return CompletableFuture.completedFuture(null);
    }

    private <T> CompletionStage<T> available(T value) {
        return closed ? failed(new IllegalStateException("Dialogue journal is closed.")) : CompletableFuture.completedFuture(value);
    }

    private static <T> CompletionStage<T> failed(Throwable failure) {
        return CompletableFuture.failedFuture(failure);
    }
}
