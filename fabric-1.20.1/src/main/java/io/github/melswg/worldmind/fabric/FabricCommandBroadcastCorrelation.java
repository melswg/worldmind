package io.github.melswg.worldmind.fabric;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Objects;
import java.util.HashSet;
import java.util.Set;

/**
 * Bounded value correlation between Fabric's command-broadcast callback and
 * its later CHAT_MESSAGE callback. Fabric 1.20.1 intentionally sends both for
 * player-executed /say and /me; text is never used to distinguish them.
 */
final class FabricCommandBroadcastCorrelation {
    private static final int MAX_RETAINED_COMMAND_MESSAGES = 128;

    private final Set<FabricSignedMessageCorrelationKey> playerCommandMessages = new HashSet<>();
    private final ArrayDeque<FabricSignedMessageCorrelationKey> insertionOrder = new ArrayDeque<>();

    synchronized void recordPlayerCommandBroadcast(FabricSignedMessageCorrelationKey signedMessage) {
        FabricSignedMessageCorrelationKey message = Objects.requireNonNull(signedMessage, "signedMessage");
        if (playerCommandMessages.add(message)) {
            insertionOrder.addLast(message);
            while (insertionOrder.size() > MAX_RETAINED_COMMAND_MESSAGES) {
                playerCommandMessages.remove(insertionOrder.removeFirst());
            }
        }
    }

    /** Returns true only for the copied signed-message identity marked as command-authored. */
    synchronized boolean consumeIfPlayerCommandBroadcast(FabricSignedMessageCorrelationKey signedMessage) {
        if (!playerCommandMessages.remove(signedMessage)) {
            return false;
        }
        Iterator<FabricSignedMessageCorrelationKey> messages = insertionOrder.iterator();
        while (messages.hasNext()) {
            if (messages.next().equals(signedMessage)) {
                messages.remove();
                break;
            }
        }
        return true;
    }

    synchronized void clear() {
        playerCommandMessages.clear();
        insertionOrder.clear();
    }
}
