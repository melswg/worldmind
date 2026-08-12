package io.github.melswg.worldmind.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melswg.worldmind.core.configuration.ChatNameColor;
import io.github.melswg.worldmind.core.conversation.AddressingSignal;
import io.github.melswg.worldmind.core.conversation.AmbientReply;
import io.github.melswg.worldmind.core.conversation.ChatBatchSealReason;
import io.github.melswg.worldmind.core.conversation.ConversationRefusal;
import io.github.melswg.worldmind.core.conversation.DeliberateSilence;
import io.github.melswg.worldmind.core.conversation.DirectReply;
import io.github.melswg.worldmind.core.conversation.ObservedPublicChatMessage;
import io.github.melswg.worldmind.core.conversation.RefusalCode;
import io.github.melswg.worldmind.core.conversation.SealedChatBatch;
import io.github.melswg.worldmind.core.conversation.ServerRequester;
import io.github.melswg.worldmind.core.conversation.UntrustedContext;
import io.github.melswg.worldmind.core.conversation.WorldIdentity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.text.Text;
import org.junit.jupiter.api.Test;

class FabricChatOutcomeRouterTest {
    private static final WorldIdentity WORLD = new WorldIdentity("save-one");
    private static final UUID FIRST_PLAYER = UUID.fromString("6eac0fe2-1ce7-47fc-9fb7-cd98778b467a");
    private static final UUID SECOND_PLAYER = UUID.fromString("821d23e6-6b5b-4d0b-b7d0-9f33b055949d");

    @Test
    void broadcastsDirectAndAmbientRepliesButKeepsSilencePlayerInvisible() {
        RecordingSink sink = new RecordingSink();
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        FabricChatOutcomeRouter router = router(sink, diagnostics);

        router.deliver(batch(AddressingSignal.EXACT, AddressingSignal.NONE), new DirectReply("the cave is east"));
        router.deliver(batch(AddressingSignal.NONE), new AmbientReply("the rain is easing"));
        router.deliver(batch(AddressingSignal.NONE), DeliberateSilence.INSTANCE);

        assertEquals(
            List.of("<Aster> the cave is east", "<Aster> the rain is easing"),
            sink.broadcasts.stream().map(Text::getString).toList()
        );
        assertTrue(sink.privateMessages.isEmpty());
        assertTrue(diagnostics.diagnostics.isEmpty());
    }

    @Test
    void exactRefusalGetsOnePrivateGenericFailureWhileLikelyAndAmbientRefusalsDoNot() {
        RecordingSink exactSink = new RecordingSink();
        RecordingDiagnostics exactDiagnostics = new RecordingDiagnostics();
        router(exactSink, exactDiagnostics).deliver(
            batch(AddressingSignal.NONE, AddressingSignal.EXACT, AddressingSignal.EXACT),
            new ConversationRefusal(RefusalCode.INVALID_PROVIDER_RESPONSE)
        );

        assertEquals(List.of(FIRST_PLAYER), exactSink.privateMessages.stream().map(PrivateMessage::recipient).toList());
        assertEquals("<Aster> I can't answer right now.", exactSink.privateMessages.get(0).message().getString());
        assertEquals(RefusalCode.INVALID_PROVIDER_RESPONSE, exactDiagnostics.diagnostics.get(0).refusalCode().orElseThrow());

        RecordingSink contextualSink = new RecordingSink();
        RecordingDiagnostics contextualDiagnostics = new RecordingDiagnostics();
        router(contextualSink, contextualDiagnostics).deliver(
            batch(AddressingSignal.LIKELY),
            new ConversationRefusal(RefusalCode.PROVIDER_UNAVAILABLE)
        );
        assertTrue(contextualSink.privateMessages.isEmpty());
        assertEquals(1, contextualDiagnostics.diagnostics.size());
    }

    @Test
    void failedDirectBroadcastUsesPriorityPrivateFallbackWhileAmbientFailureIsDiagnosticOnly() {
        RecordingSink directSink = new RecordingSink();
        directSink.failBroadcast = true;
        RecordingDiagnostics directDiagnostics = new RecordingDiagnostics();
        router(directSink, directDiagnostics).deliver(
            batch(AddressingSignal.NONE, AddressingSignal.LIKELY, AddressingSignal.EXACT),
            new DirectReply("answer")
        );
        assertEquals(List.of(FIRST_PLAYER), directSink.privateMessages.stream().map(PrivateMessage::recipient).toList());
        assertEquals(FabricChatDeliveryDiagnosticKind.DIRECT_DELIVERY_FAILED, directDiagnostics.diagnostics.get(0).kind());

        RecordingSink ambientSink = new RecordingSink();
        ambientSink.failBroadcast = true;
        RecordingDiagnostics ambientDiagnostics = new RecordingDiagnostics();
        router(ambientSink, ambientDiagnostics).deliver(batch(AddressingSignal.NONE), new AmbientReply("weather"));
        assertTrue(ambientSink.privateMessages.isEmpty());
        assertEquals(FabricChatDeliveryDiagnosticKind.AMBIENT_DELIVERY_FAILED, ambientDiagnostics.diagnostics.get(0).kind());
    }

    @Test
    void suppressesMismatchedWorldsAndInactiveRuntimes() {
        RecordingSink sink = new RecordingSink();
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        FabricChatOutcomeRouter inactive = new FabricChatOutcomeRouter(
            WORLD,
            "Aster",
            ChatNameColor.LIGHT_PURPLE,
            () -> false,
            sink,
            diagnostics
        );
        inactive.deliver(batch(AddressingSignal.EXACT), new DirectReply("answer"));
        router(sink, diagnostics).deliver(batchFor(new WorldIdentity("other-save"), AddressingSignal.EXACT), new DirectReply("answer"));

        assertTrue(sink.broadcasts.isEmpty());
        assertTrue(sink.privateMessages.isEmpty());
        assertTrue(diagnostics.diagnostics.isEmpty());
    }

    private FabricChatOutcomeRouter router(RecordingSink sink, RecordingDiagnostics diagnostics) {
        return new FabricChatOutcomeRouter(
            WORLD,
            "Aster",
            ChatNameColor.LIGHT_PURPLE,
            () -> true,
            sink,
            diagnostics
        );
    }

    private SealedChatBatch batch(AddressingSignal... signals) {
        return batchFor(WORLD, signals);
    }

    private SealedChatBatch batchFor(WorldIdentity world, AddressingSignal... signals) {
        List<ObservedPublicChatMessage> messages = new ArrayList<>();
        for (int index = 0; index < signals.length; index++) {
            UUID playerId = index % 2 == 0 ? FIRST_PLAYER : SECOND_PLAYER;
            messages.add(new ObservedPublicChatMessage(
                index + 1L,
                new ServerRequester(playerId, index % 2 == 0 ? "Mira" : "Rowan"),
                "message-" + (index + 1),
                signals[index],
                Instant.EPOCH,
                List.of(new UntrustedContext("vanilla-game-context", "weather=clear"))
            ));
        }
        return new SealedChatBatch(
            world,
            messages,
            ChatBatchSealReason.ADDRESSING_SIGNAL,
            List.of(new UntrustedContext("vanilla-game-context", "weather=clear"))
        );
    }

    private static final class RecordingSink implements ServerChatSink {
        private final List<Text> broadcasts = new ArrayList<>();
        private final List<PrivateMessage> privateMessages = new ArrayList<>();
        private boolean failBroadcast;

        @Override
        public void broadcast(Text message) {
            if (failBroadcast) {
                throw new IllegalStateException("deterministic broadcast failure");
            }
            broadcasts.add(message);
        }

        @Override
        public boolean sendPrivate(UUID playerId, Text message) {
            privateMessages.add(new PrivateMessage(playerId, message));
            return true;
        }
    }

    private record PrivateMessage(UUID recipient, Text message) {
    }

    private static final class RecordingDiagnostics implements FabricChatDiagnostics {
        private final List<FabricChatDeliveryDiagnostic> diagnostics = new ArrayList<>();

        @Override
        public void record(FabricChatDeliveryDiagnostic diagnostic) {
            diagnostics.add(diagnostic);
        }
    }
}
