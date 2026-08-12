package io.github.melswg.worldmind.fabric;

import io.github.melswg.worldmind.core.conversation.RefusalCode;
import java.util.Objects;
import java.util.Optional;

/** A diagnostic deliberately limited to category, sequence range, and refusal code. */
record FabricChatDeliveryDiagnostic(
    FabricChatDeliveryDiagnosticKind kind,
    long firstSequence,
    long lastSequence,
    Optional<RefusalCode> refusalCode
) {
    FabricChatDeliveryDiagnostic {
        Objects.requireNonNull(kind, "kind");
        if (firstSequence <= 0 || lastSequence < firstSequence) {
            throw new IllegalArgumentException("A diagnostic sequence range must be positive and ordered.");
        }
        refusalCode = Objects.requireNonNull(refusalCode, "refusalCode");
    }

    static FabricChatDeliveryDiagnostic refusal(long firstSequence, long lastSequence, RefusalCode code) {
        return new FabricChatDeliveryDiagnostic(
            FabricChatDeliveryDiagnosticKind.REFUSAL,
            firstSequence,
            lastSequence,
            Optional.of(Objects.requireNonNull(code, "code"))
        );
    }

    static FabricChatDeliveryDiagnostic delivery(
        FabricChatDeliveryDiagnosticKind kind,
        long firstSequence,
        long lastSequence
    ) {
        return new FabricChatDeliveryDiagnostic(kind, firstSequence, lastSequence, Optional.empty());
    }
}
