package io.github.melswg.worldmind.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melswg.worldmind.core.configuration.ChatBatchingConfiguration;
import io.github.melswg.worldmind.core.configuration.ExternalSecretReference;
import io.github.melswg.worldmind.core.configuration.GenerationParameters;
import io.github.melswg.worldmind.core.configuration.LoreMaterial;
import io.github.melswg.worldmind.core.configuration.ProviderConfiguration;
import io.github.melswg.worldmind.core.configuration.ProviderEndpoint;
import io.github.melswg.worldmind.core.configuration.RequestQueueConfiguration;
import io.github.melswg.worldmind.core.configuration.ResponseLengthLimit;
import io.github.melswg.worldmind.core.configuration.ValidatedWorldmindConfiguration;
import io.github.melswg.worldmind.core.configuration.WorldmindGlobalConfiguration;
import io.github.melswg.worldmind.core.configuration.WorldmindProfile;
import io.github.melswg.worldmind.core.conversation.AmbientReply;
import io.github.melswg.worldmind.core.conversation.ChatBatchCoordinator;
import io.github.melswg.worldmind.core.conversation.CharacterNameAddressingDetector;
import io.github.melswg.worldmind.core.conversation.ConversationOutcome;
import io.github.melswg.worldmind.core.conversation.ConversationRefusal;
import io.github.melswg.worldmind.core.conversation.DeliberateSilence;
import io.github.melswg.worldmind.core.conversation.DirectReply;
import io.github.melswg.worldmind.core.conversation.ProviderCapabilities;
import io.github.melswg.worldmind.core.conversation.ObservedPublicChatMessage;
import io.github.melswg.worldmind.core.conversation.RefusalCode;
import io.github.melswg.worldmind.core.conversation.SealedChatBatch;
import io.github.melswg.worldmind.core.conversation.ServerRequester;
import io.github.melswg.worldmind.core.conversation.UntrustedContext;
import io.github.melswg.worldmind.core.conversation.WorldIdentity;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Exercises Ticket 09's one-way handoff before its Fabric presentation adapter. */
class ChatDeliveryAcceptanceTest {
    private static final WorldIdentity WORLD = new WorldIdentity("acceptance-save");
    private static final ServerRequester MIRA = new ServerRequester(
        UUID.fromString("6eac0fe2-1ce7-47fc-9fb7-cd98778b467a"),
        "Mira"
    );
    private static final ProviderCapabilities CAPABILITIES = new ProviderCapabilities(true);
    private static final UntrustedContext CONTEXT = new UntrustedContext(
        "vanilla-game-context",
        "dimension=minecraft:overworld; weather=clear"
    );
    private long nextSequence;

    @Test
    void sendsOneOrderedSealedBatchThroughTheExistingServiceOnlyWhenTheServerSchedulerRuns() {
        WorldmindAcceptanceScenario scenario = WorldmindTestkit.scenario();
        scenario.languageModel().willDirectReplyWith("The cave is east.");
        List<DeliveredDecision> delivered = new ArrayList<>();
        ChatBatchCoordinator batcher = decidingBatcher(scenario, new ChatBatchingConfiguration(8, 5_000, 4_000), delivered);

        observe(batcher, "The fire is low.");
        observe(batcher, "Aster!");

        assertEquals(1, scenario.languageModel().receivedRequests().size());
        assertTrue(delivered.isEmpty());
        assertEquals(
            List.of("The fire is low.", "Aster!"),
            scenario.languageModel().onlyReceivedRequest().promptLayers().get(6).fragments().stream()
                .map(fragment -> fragment.content().substring(fragment.content().indexOf("message: ") + "message: ".length()))
                .toList()
        );

        scenario.serverScheduler().runUntilIdle();

        assertEquals(1, delivered.size());
        assertEquals(new DirectReply("The cave is east."), delivered.get(0).outcome());
    }

    @Test
    void preservesContextualAmbientAndSilentDecisionsAndReturnsExactFailuresWithoutEscapingOwnership() {
        WorldmindAcceptanceScenario ambientScenario = WorldmindTestkit.scenario();
        ambientScenario.languageModel().willAmbientReplyWith("The rain will make the bridge slick.");
        List<DeliveredDecision> ambient = new ArrayList<>();
        observe(decidingBatcher(ambientScenario, new ChatBatchingConfiguration(1, 5_000, 4_000), ambient), "The bridge is wet.");
        ambientScenario.serverScheduler().runUntilIdle();
        assertEquals(new AmbientReply("The rain will make the bridge slick."), ambient.get(0).outcome());

        WorldmindAcceptanceScenario silentScenario = WorldmindTestkit.scenario();
        silentScenario.languageModel().willRemainSilent();
        List<DeliveredDecision> silent = new ArrayList<>();
        observe(decidingBatcher(silentScenario, new ChatBatchingConfiguration(1, 5_000, 4_000), silent), "I have ten cobblestone.");
        silentScenario.serverScheduler().runUntilIdle();
        assertEquals(DeliberateSilence.INSTANCE, silent.get(0).outcome());

        WorldmindAcceptanceScenario exactFailureScenario = WorldmindTestkit.scenario();
        exactFailureScenario.languageModel().willRespondWith("SILENT");
        List<DeliveredDecision> exactFailure = new ArrayList<>();
        observe(decidingBatcher(exactFailureScenario, new ChatBatchingConfiguration(8, 5_000, 4_000), exactFailure), "Aster!");
        exactFailureScenario.serverScheduler().runUntilIdle();
        ConversationRefusal refusal = assertInstanceOf(ConversationRefusal.class, exactFailure.get(0).outcome());
        assertEquals(RefusalCode.INVALID_PROVIDER_RESPONSE, refusal.code());
    }

    @Test
    void keepsNextSealedBatchInSequenceUntilThePriorDecisionAndConsumerCompletionFinish() {
        WorldmindAcceptanceScenario scenario = WorldmindTestkit.scenario();
        scenario.languageModel().willRespondWithSequence("DIRECT_REPLY\nfirst", "DIRECT_REPLY\nsecond");
        List<DeliveredDecision> delivered = new ArrayList<>();
        ChatBatchCoordinator batcher = decidingBatcher(scenario, new ChatBatchingConfiguration(8, 5_000, 4_000), delivered);

        observe(batcher, "Aster!");
        observe(batcher, "Aster?");

        assertEquals(1, scenario.languageModel().receivedRequests().size());
        assertTrue(delivered.isEmpty());
        scenario.serverScheduler().runUntilIdle();

        assertEquals(2, scenario.languageModel().receivedRequests().size());
        assertEquals(
            List.of(new DirectReply("first"), new DirectReply("second")),
            delivered.stream().map(DeliveredDecision::outcome).toList()
        );
        assertEquals(
            List.of(List.of(1L), List.of(2L)),
            delivered.stream().map(delivery -> delivery.batch().messages().stream().map(message -> message.sequence()).toList()).toList()
        );
    }

    private ChatBatchCoordinator decidingBatcher(
        WorldmindAcceptanceScenario scenario,
        ChatBatchingConfiguration batching,
        List<DeliveredDecision> delivered
    ) {
        return scenario.decidingChatBatcher(
            batching,
            "Aster",
            configuration(batching),
            CAPABILITIES,
            (batch, outcome) -> delivered.add(new DeliveredDecision(batch, outcome))
        );
    }

    private void observe(ChatBatchCoordinator batcher, String text) {
        batcher.observe(new ObservedPublicChatMessage(
            ++nextSequence, MIRA, text, new CharacterNameAddressingDetector("Aster").detect(text), Instant.EPOCH, List.of(CONTEXT)
        ), WORLD);
    }

    private ValidatedWorldmindConfiguration configuration(ChatBatchingConfiguration batching) {
        return new ValidatedWorldmindConfiguration(
            new WorldmindGlobalConfiguration(
                WorldmindGlobalConfiguration.V1_SCHEMA_VERSION,
                true,
                "acceptance-profile",
                new ProviderConfiguration(
                    "custom-openai-compatible",
                    new ProviderEndpoint(URI.create("https://api.example.invalid/v1/chat/completions")),
                    "example-model",
                    new GenerationParameters(Optional.of(0.4), Optional.empty(), Optional.of(120)),
                    new ExternalSecretReference("env:WORLDMIND_ACCEPTANCE_KEY")
                ),
                batching,
                new RequestQueueConfiguration(16, 2)
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

    private record DeliveredDecision(SealedChatBatch batch, ConversationOutcome outcome) {
    }
}
