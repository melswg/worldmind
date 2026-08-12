package io.github.melswg.worldmind.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melswg.worldmind.core.configuration.ChatBatchingConfiguration;
import io.github.melswg.worldmind.core.configuration.ExternalSecretReference;
import io.github.melswg.worldmind.core.configuration.GenerationParameters;
import io.github.melswg.worldmind.core.configuration.LoreMaterial;
import io.github.melswg.worldmind.core.configuration.ProviderConfiguration;
import io.github.melswg.worldmind.core.configuration.ProviderEndpoint;
import io.github.melswg.worldmind.core.configuration.ResponseLengthLimit;
import io.github.melswg.worldmind.core.configuration.ValidatedWorldmindConfiguration;
import io.github.melswg.worldmind.core.configuration.WorldmindGlobalConfiguration;
import io.github.melswg.worldmind.core.configuration.WorldmindProfile;
import io.github.melswg.worldmind.core.conversation.AddressingSignal;
import io.github.melswg.worldmind.core.conversation.CapturedPublicChatMessage;
import io.github.melswg.worldmind.core.conversation.ChatBatchAdmission;
import io.github.melswg.worldmind.core.conversation.ProviderCapabilities;
import io.github.melswg.worldmind.core.conversation.ServerRequester;
import io.github.melswg.worldmind.core.conversation.UntrustedContext;
import io.github.melswg.worldmind.core.conversation.WorldIdentity;
import io.github.melswg.worldmind.testkit.WorldmindAcceptanceScenario;
import io.github.melswg.worldmind.testkit.WorldmindTestkit;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.text.Text;
import org.junit.jupiter.api.Test;

class FabricChatObservationRuntimeTest {
    private static final WorldIdentity WORLD = new WorldIdentity("runtime-save");
    private static final ServerRequester PLAYER = new ServerRequester(
        UUID.fromString("6eac0fe2-1ce7-47fc-9fb7-cd98778b467a"),
        "Mira"
    );
    private static final UntrustedContext CONTEXT = new UntrustedContext("vanilla-game-context", "weather=clear");

    @Test
    void connectsCopiedAcceptedChatToOneProviderDecisionAndPublicGameMessageDelivery() {
        WorldmindAcceptanceScenario scenario = WorldmindTestkit.scenario();
        scenario.languageModel().willDirectReplyWith("The cave is east.");
        RecordingSink sink = new RecordingSink();
        FabricChatObservationRuntime runtime = runtime(scenario, sink);

        assertEquals(ChatBatchAdmission.SEALED_FOR_HANDOFF, runtime.observeCapturedPublicChat(captured("Aster!", AddressingSignal.EXACT), WORLD));
        assertEquals(1, scenario.languageModel().receivedRequests().size());
        assertTrue(sink.broadcasts.isEmpty());

        scenario.serverScheduler().runUntilIdle();

        assertEquals(List.of("<Aster> The cave is east."), sink.broadcasts.stream().map(Text::getString).toList());
    }

    @Test
    void doesNotStartOrDeliverAcrossWorldIdentityAndDropsQueuedAndLateWorkAfterClose() {
        WorldmindAcceptanceScenario scenario = WorldmindTestkit.scenario();
        scenario.languageModel().willDirectReplyWith("Never deliver.");
        RecordingSink sink = new RecordingSink();
        FabricChatObservationRuntime runtime = runtime(scenario, sink);

        assertEquals(
            ChatBatchAdmission.IGNORED_AFTER_CLOSE,
            runtime.observeCapturedPublicChat(captured("Aster!", AddressingSignal.EXACT), new WorldIdentity("other-save"))
        );
        assertTrue(scenario.languageModel().receivedRequests().isEmpty());

        runtime.observeCapturedPublicChat(captured("Aster!", AddressingSignal.EXACT), WORLD);
        assertEquals(1, scenario.languageModel().receivedRequests().size());
        runtime.close();
        scenario.serverScheduler().runUntilIdle();

        assertTrue(sink.broadcasts.isEmpty());
        assertTrue(sink.privateMessages.isEmpty());
        assertEquals(ChatBatchAdmission.IGNORED_AFTER_CLOSE, runtime.observeCapturedPublicChat(captured("Aster!", AddressingSignal.EXACT), WORLD));
    }

    @Test
    void keepsNextExactBatchBehindThePriorDeliveredBatch() {
        WorldmindAcceptanceScenario scenario = WorldmindTestkit.scenario();
        scenario.languageModel().willRespondWithSequence("DIRECT_REPLY\nfirst", "DIRECT_REPLY\nsecond");
        RecordingSink sink = new RecordingSink();
        FabricChatObservationRuntime runtime = runtime(scenario, sink);

        runtime.observeCapturedPublicChat(captured("Aster!", AddressingSignal.EXACT), WORLD);
        runtime.observeCapturedPublicChat(captured("Aster?", AddressingSignal.EXACT), WORLD);
        assertEquals(1, scenario.languageModel().receivedRequests().size());

        scenario.serverScheduler().runUntilIdle();

        assertEquals(
            List.of("<Aster> first", "<Aster> second"),
            sink.broadcasts.stream().map(Text::getString).toList()
        );
        assertFalse(sink.broadcasts.isEmpty());
    }

    private FabricChatObservationRuntime runtime(WorldmindAcceptanceScenario scenario, RecordingSink sink) {
        return new FabricChatObservationRuntime(
            WORLD,
            configuration(),
            scenario.clock(),
            scenario.serverScheduler(),
            () -> { },
            () -> { },
            scenario.applicationService(),
            new ProviderCapabilities(true),
            sink,
            diagnostic -> { }
        );
    }

    private CapturedPublicChatMessage captured(String message, AddressingSignal signal) {
        return new CapturedPublicChatMessage(PLAYER, message, signal, Instant.EPOCH, List.of(CONTEXT));
    }

    private ValidatedWorldmindConfiguration configuration() {
        ChatBatchingConfiguration batching = new ChatBatchingConfiguration(8, 5_000, 4_000);
        return new ValidatedWorldmindConfiguration(
            new WorldmindGlobalConfiguration(
                WorldmindGlobalConfiguration.V1_SCHEMA_VERSION,
                true,
                "runtime-profile",
                new ProviderConfiguration(
                    "custom-openai-compatible",
                    new ProviderEndpoint(URI.create("https://api.example.invalid/v1/chat/completions")),
                    "example-model",
                    new GenerationParameters(Optional.of(0.4), Optional.empty(), Optional.of(120)),
                    new ExternalSecretReference("env:WORLDMIND_ACCEPTANCE_KEY")
                ),
                batching
            ),
            new WorldmindProfile(
                WorldmindProfile.V1_SCHEMA_VERSION,
                "Aster",
                "A thoughtful guide.",
                "Keep the peace.",
                List.of(new LoreMaterial("lore/setting.md", "The old observatory watches the valley.")),
                "calm",
                new ResponseLengthLimit(280)
            )
        );
    }

    private static final class RecordingSink implements ServerChatSink {
        private final List<Text> broadcasts = new ArrayList<>();
        private final List<PrivateMessage> privateMessages = new ArrayList<>();

        @Override
        public void broadcast(Text message) {
            broadcasts.add(message);
        }

        @Override
        public boolean sendPrivate(UUID playerId, Text message) {
            privateMessages.add(new PrivateMessage(playerId, message));
            return true;
        }
    }

    private record PrivateMessage(UUID playerId, Text message) {
    }
}
