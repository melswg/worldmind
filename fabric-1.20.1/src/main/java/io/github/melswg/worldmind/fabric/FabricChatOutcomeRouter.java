package io.github.melswg.worldmind.fabric;

import io.github.melswg.worldmind.core.configuration.ChatNameColor;
import io.github.melswg.worldmind.core.conversation.AddressingSignal;
import io.github.melswg.worldmind.core.conversation.AmbientReply;
import io.github.melswg.worldmind.core.conversation.ConversationOutcome;
import io.github.melswg.worldmind.core.conversation.ConversationRefusal;
import io.github.melswg.worldmind.core.conversation.DirectReply;
import io.github.melswg.worldmind.core.conversation.ObservedPublicChatMessage;
import io.github.melswg.worldmind.core.conversation.SealedChatBatch;
import io.github.melswg.worldmind.core.conversation.ServerRequester;
import io.github.melswg.worldmind.core.conversation.WorldIdentity;
import io.github.melswg.worldmind.core.journal.JournalDeliveryReport;
import io.github.melswg.worldmind.core.journal.JournalDeliveryStatus;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import net.minecraft.text.Text;

/** Converts one completed core decision into safe server-chat delivery or silence. */
final class FabricChatOutcomeRouter {
    private final WorldIdentity ownedWorld;
    private final String characterName;
    private final ChatNameColor chatNameColor;
    private final BooleanSupplier active;
    private final ServerChatSink chatSink;
    private final FabricChatDiagnostics diagnostics;

    FabricChatOutcomeRouter(
        WorldIdentity ownedWorld,
        String characterName,
        ChatNameColor chatNameColor,
        BooleanSupplier active,
        ServerChatSink chatSink,
        FabricChatDiagnostics diagnostics
    ) {
        this.ownedWorld = Objects.requireNonNull(ownedWorld, "ownedWorld");
        this.characterName = requireText(characterName, "characterName");
        this.chatNameColor = Objects.requireNonNull(chatNameColor, "chatNameColor");
        this.active = Objects.requireNonNull(active, "active");
        this.chatSink = Objects.requireNonNull(chatSink, "chatSink");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    JournalDeliveryReport deliver(SealedChatBatch batch, ConversationOutcome outcome) {
        Objects.requireNonNull(batch, "batch");
        Objects.requireNonNull(outcome, "outcome");
        if (!active.getAsBoolean() || !ownedWorld.equals(batch.worldIdentity())) {
            return new JournalDeliveryReport(JournalDeliveryStatus.ROUTING_SKIPPED, Optional.empty());
        }
        if (outcome instanceof DirectReply directReply) {
            return deliverDirect(batch, directReply);
        } else if (outcome instanceof AmbientReply ambientReply) {
            return deliverAmbient(batch, ambientReply);
        } else if (outcome instanceof ConversationRefusal refusal) {
            return deliverRefusal(batch, refusal);
        }
        // DeliberateSilence deliberately has no player-facing or diagnostic output.
        return JournalDeliveryReport.noOutput();
    }

    void notifyStorageUnavailable(io.github.melswg.worldmind.core.conversation.CapturedPublicChatMessage captured) {
        Objects.requireNonNull(captured, "captured");
        if (active.getAsBoolean() && captured.addressingSignal() == AddressingSignal.EXACT) {
            sendPrivateFailure(captured.requester());
        }
    }

    private JournalDeliveryReport deliverDirect(SealedChatBatch batch, DirectReply reply) {
        try {
            chatSink.broadcast(render(reply.text()));
            return new JournalDeliveryReport(JournalDeliveryStatus.PUBLIC_REPLY_DELIVERED, Optional.of(reply.text()));
        } catch (RuntimeException failure) {
            record(FabricChatDeliveryDiagnostic.delivery(
                FabricChatDeliveryDiagnosticKind.DIRECT_DELIVERY_FAILED,
                firstSequence(batch),
                lastSequence(batch)
            ));
            return fallbackForDirectDeliveryFailure(batch);
        }
    }

    private JournalDeliveryReport deliverAmbient(SealedChatBatch batch, AmbientReply reply) {
        try {
            chatSink.broadcast(render(reply.text()));
            return new JournalDeliveryReport(JournalDeliveryStatus.PUBLIC_REPLY_DELIVERED, Optional.of(reply.text()));
        } catch (RuntimeException failure) {
            record(FabricChatDeliveryDiagnostic.delivery(
                FabricChatDeliveryDiagnosticKind.AMBIENT_DELIVERY_FAILED,
                firstSequence(batch),
                lastSequence(batch)
            ));
            return new JournalDeliveryReport(JournalDeliveryStatus.PUBLIC_REPLY_DELIVERY_FAILED, Optional.empty());
        }
    }

    private JournalDeliveryReport deliverRefusal(SealedChatBatch batch, ConversationRefusal refusal) {
        record(FabricChatDeliveryDiagnostic.refusal(firstSequence(batch), lastSequence(batch), refusal.code()));
        return latestWithSignal(batch.messages(), AddressingSignal.EXACT)
            .map(this::sendPrivateFailure)
            .orElseGet(JournalDeliveryReport::noOutput);
    }

    private JournalDeliveryReport fallbackForDirectDeliveryFailure(SealedChatBatch batch) {
        Optional<ServerRequester> recipient = latestWithSignal(batch.messages(), AddressingSignal.EXACT)
            .or(() -> latestWithSignal(batch.messages(), AddressingSignal.LIKELY))
            .or(() -> Optional.of(batch.messages().get(batch.messages().size() - 1).requester()));
        return recipient.map(this::sendPrivateFailure)
            .orElse(new JournalDeliveryReport(JournalDeliveryStatus.PUBLIC_REPLY_DELIVERY_FAILED, Optional.empty()));
    }

    private JournalDeliveryReport sendPrivateFailure(ServerRequester requester) {
        try {
            if (!chatSink.sendPrivate(requester.playerId(), FabricWorldmindChatRenderer.unavailable(characterName, chatNameColor))) {
                return new JournalDeliveryReport(JournalDeliveryStatus.PRIVATE_UNAVAILABLE_UNDELIVERABLE, Optional.empty());
            }
            return new JournalDeliveryReport(JournalDeliveryStatus.PRIVATE_UNAVAILABLE_DELIVERED, Optional.empty());
        } catch (RuntimeException failure) {
            return new JournalDeliveryReport(JournalDeliveryStatus.PRIVATE_UNAVAILABLE_UNDELIVERABLE, Optional.empty());
        }
    }

    private Text render(String text) {
        return FabricWorldmindChatRenderer.reply(characterName, chatNameColor, text);
    }

    private void record(FabricChatDeliveryDiagnostic diagnostic) {
        try {
            diagnostics.record(diagnostic);
        } catch (RuntimeException ignored) {
            // Diagnostics cannot break batch ownership or player delivery.
        }
    }

    private static Optional<ServerRequester> latestWithSignal(
        List<ObservedPublicChatMessage> messages,
        AddressingSignal signal
    ) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            ObservedPublicChatMessage message = messages.get(index);
            if (message.addressingSignal() == signal) {
                return Optional.of(message.requester());
            }
        }
        return Optional.empty();
    }

    private static long firstSequence(SealedChatBatch batch) {
        return batch.messages().get(0).sequence();
    }

    private static long lastSequence(SealedChatBatch batch) {
        List<ObservedPublicChatMessage> messages = batch.messages();
        return messages.get(messages.size() - 1).sequence();
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }
}
