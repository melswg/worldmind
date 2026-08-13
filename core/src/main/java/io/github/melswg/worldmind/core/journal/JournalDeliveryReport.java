package io.github.melswg.worldmind.core.journal;

import java.util.Objects;
import java.util.Optional;

/** Delivery audit data. Response text is present only when that response reached public chat. */
public record JournalDeliveryReport(JournalDeliveryStatus status, Optional<String> deliveredResponse) {
    public JournalDeliveryReport {
        Objects.requireNonNull(status, "status");
        deliveredResponse = Optional.ofNullable(Objects.requireNonNull(deliveredResponse, "deliveredResponse").orElse(null));
        if (deliveredResponse.isPresent() && status != JournalDeliveryStatus.PUBLIC_REPLY_DELIVERED) {
            throw new IllegalArgumentException("Only a public delivered reply may carry response text.");
        }
    }

    public static JournalDeliveryReport noOutput() {
        return new JournalDeliveryReport(JournalDeliveryStatus.NO_OUTPUT, Optional.empty());
    }
}
