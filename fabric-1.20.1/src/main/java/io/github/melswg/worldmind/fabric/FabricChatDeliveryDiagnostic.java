package io.github.melswg.worldmind.fabric;

import io.github.melswg.worldmind.core.conversation.AsyncWorkKind;
import io.github.melswg.worldmind.core.conversation.AsyncWorkSnapshot;
import io.github.melswg.worldmind.core.conversation.RefusalCode;
import io.github.melswg.worldmind.core.conversation.WorldIdentity;
import java.util.Objects;
import java.util.Optional;

/** A diagnostic deliberately limited to safe category, sequence, and queue accounting data. */
record FabricChatDeliveryDiagnostic(
    FabricChatDeliveryDiagnosticKind kind,
    long firstSequence,
    long lastSequence,
    Optional<RefusalCode> refusalCode,
    Optional<AsyncWorkKind> workKind,
    Optional<AsyncWorkSnapshot> queueSnapshot
) {
    FabricChatDeliveryDiagnostic {
        Objects.requireNonNull(kind, "kind");
        if (firstSequence <= 0 || lastSequence < firstSequence) {
            throw new IllegalArgumentException("A diagnostic sequence range must be positive and ordered.");
        }
        refusalCode = Objects.requireNonNull(refusalCode, "refusalCode");
        workKind = Objects.requireNonNull(workKind, "workKind");
        queueSnapshot = Objects.requireNonNull(queueSnapshot, "queueSnapshot");
    }

    static FabricChatDeliveryDiagnostic refusal(long firstSequence, long lastSequence, RefusalCode code) {
        return new FabricChatDeliveryDiagnostic(
            FabricChatDeliveryDiagnosticKind.REFUSAL,
            firstSequence,
            lastSequence,
            Optional.of(Objects.requireNonNull(code, "code")),
            Optional.empty(),
            Optional.empty()
        );
    }

    static FabricChatDeliveryDiagnostic delivery(
        FabricChatDeliveryDiagnosticKind kind,
        long firstSequence,
        long lastSequence
    ) {
        return new FabricChatDeliveryDiagnostic(
            kind, firstSequence, lastSequence, Optional.empty(), Optional.empty(), Optional.empty()
        );
    }

    static FabricChatDeliveryDiagnostic queueRejection(
        AsyncWorkKind workKind,
        WorldIdentity worldIdentity,
        long firstSequence,
        long lastSequence,
        AsyncWorkSnapshot queueSnapshot
    ) {
        return new FabricChatDeliveryDiagnostic(
            FabricChatDeliveryDiagnosticKind.QUEUE_REJECTED,
            firstSequence,
            lastSequence,
            Optional.empty(),
            Optional.of(Objects.requireNonNull(workKind, "workKind")),
            Optional.of(Objects.requireNonNull(queueSnapshot, "queueSnapshot"))
        );
    }
}
