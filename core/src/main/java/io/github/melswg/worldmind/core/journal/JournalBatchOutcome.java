package io.github.melswg.worldmind.core.journal;

import io.github.melswg.worldmind.core.conversation.RefusalCode;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Terminal audit record for one persisted batch. */
public record JournalBatchOutcome(
    UUID batchId,
    ProviderAttemptOutcome providerAttemptOutcome,
    Optional<JournalParticipationDecision> decision,
    Optional<RefusalCode> refusalCode,
    JournalDeliveryReport delivery,
    Instant completedAt
) {
    public JournalBatchOutcome {
        Objects.requireNonNull(batchId, "batchId");
        Objects.requireNonNull(providerAttemptOutcome, "providerAttemptOutcome");
        decision = Optional.ofNullable(Objects.requireNonNull(decision, "decision").orElse(null));
        refusalCode = Optional.ofNullable(Objects.requireNonNull(refusalCode, "refusalCode").orElse(null));
        Objects.requireNonNull(delivery, "delivery");
        Objects.requireNonNull(completedAt, "completedAt");
        if (decision.isPresent() && providerAttemptOutcome != ProviderAttemptOutcome.SUCCEEDED) {
            throw new IllegalArgumentException("Only a successful provider attempt may have a participation decision.");
        }
        if (refusalCode.isPresent() && decision.isPresent()) {
            throw new IllegalArgumentException("A decision and refusal code are mutually exclusive.");
        }
    }
}
