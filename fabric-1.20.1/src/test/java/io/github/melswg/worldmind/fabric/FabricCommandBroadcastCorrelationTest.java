package io.github.melswg.worldmind.fabric;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FabricCommandBroadcastCorrelationTest {
    @Test
    void distinguishesCommandAuthoredBroadcastsByEventCorrelationNotByTheirRenderedText() {
        FabricCommandBroadcastCorrelation correlation = new FabricCommandBroadcastCorrelation();
        FabricSignedMessageCorrelationKey ordinaryPublicChatMessage = key(1);
        FabricSignedMessageCorrelationKey playerCommandBroadcast = key(2);
        String indistinguishableRenderedText = "Майни, привет";

        assertFalse(correlation.consumeIfPlayerCommandBroadcast(ordinaryPublicChatMessage));
        correlation.recordPlayerCommandBroadcast(playerCommandBroadcast);
        assertTrue(correlation.consumeIfPlayerCommandBroadcast(playerCommandBroadcast), indistinguishableRenderedText);
        assertFalse(correlation.consumeIfPlayerCommandBroadcast(playerCommandBroadcast));

        correlation.recordPlayerCommandBroadcast(playerCommandBroadcast);
        correlation.clear();
        assertFalse(correlation.consumeIfPlayerCommandBroadcast(playerCommandBroadcast));
    }

    private FabricSignedMessageCorrelationKey key(int index) {
        return new FabricSignedMessageCorrelationKey(
            UUID.fromString("6eac0fe2-1ce7-47fc-9fb7-cd98778b467a"),
            UUID.fromString("c193fe9d-1fd9-4d29-9a5f-f7b0d2e4c741"),
            index,
            Instant.EPOCH,
            7L
        );
    }
}
