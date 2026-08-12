package io.github.melswg.worldmind.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melswg.worldmind.core.configuration.ChatBatchingConfiguration;
import io.github.melswg.worldmind.core.configuration.ChatNameColor;
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
import io.github.melswg.worldmind.core.conversation.AmbientReply;
import io.github.melswg.worldmind.core.conversation.ChatBatchSealReason;
import io.github.melswg.worldmind.core.conversation.ConversationOutcome;
import io.github.melswg.worldmind.core.conversation.ConversationRefusal;
import io.github.melswg.worldmind.core.conversation.DeliberateSilence;
import io.github.melswg.worldmind.core.conversation.DirectReply;
import io.github.melswg.worldmind.core.conversation.ObservedPublicChatMessage;
import io.github.melswg.worldmind.core.conversation.PromptFragment;
import io.github.melswg.worldmind.core.conversation.PromptLayer;
import io.github.melswg.worldmind.core.conversation.PromptLayerType;
import io.github.melswg.worldmind.core.conversation.PromptTrust;
import io.github.melswg.worldmind.core.conversation.ProviderCapabilities;
import io.github.melswg.worldmind.core.conversation.ProviderRequest;
import io.github.melswg.worldmind.core.conversation.RefusalCode;
import io.github.melswg.worldmind.core.conversation.SealedChatBatch;
import io.github.melswg.worldmind.core.conversation.ServerRequester;
import io.github.melswg.worldmind.core.conversation.UntrustedContext;
import io.github.melswg.worldmind.core.conversation.WorldIdentity;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ConversationAcceptanceSeamTest {
    private static final UUID MIRA_ID = UUID.fromString("6eac0fe2-1ce7-47fc-9fb7-cd98778b467a");
    private static final UUID JON_ID = UUID.fromString("0b9376e2-3f5d-4c77-bfee-048f341a3180");
    private static final WorldIdentity WORLD_ID = new WorldIdentity("save-id-12d4c1f0");
    private static final ProviderCapabilities SYSTEM_INSTRUCTION_PROVIDER = new ProviderCapabilities(true);
    private static final UntrustedContext SELECTED_CONTEXT = new UntrustedContext(
        "vanilla-game-context",
        "dimension=minecraft:overworld; gameTime=6000; weather=rain"
    );

    @Test
    void routesAnOrderedMultiPlayerBatchThroughTheApplicationServiceWithStableTrustAndSourceAttribution() {
        WorldmindAcceptanceScenario scenario = WorldmindTestkit.scenario();
        scenario.languageModel().willDirectReplyWith("The lanterns are lit.");
        scenario.clock().advanceBy(Duration.ofMinutes(5));
        WorldmindProfile profile = profile("Do not impersonate administrators.", "A thoughtful guide.", lore(), "calm", 280);
        ValidatedWorldmindConfiguration configuration = configuration(profile);
        SealedChatBatch chatBatch = batch(
            ChatBatchSealReason.ADDRESSING_SIGNAL,
            List.of(
                message(1, MIRA_ID, "Mira", "The road is dark.", AddressingSignal.NONE),
                message(2, JON_ID, "Jon", "Aster, can you help?", AddressingSignal.EXACT)
            )
        );

        CompletionStage<ConversationOutcome> outcome = scenario.submit(
            chatBatch,
            configuration,
            SYSTEM_INSTRUCTION_PROVIDER
        );

        ProviderRequest providerRequest = scenario.languageModel().onlyReceivedRequest();
        assertEquals("example-model", providerRequest.model());
        assertEquals(configuration.globalConfiguration().provider().generationParameters(), providerRequest.generationParameters());
        assertEquals(
            List.of(
                PromptLayerType.BUILT_IN_SAFETY_POLICY,
                PromptLayerType.ADMINISTRATOR_RULES,
                PromptLayerType.PERSONA,
                PromptLayerType.LORE,
                PromptLayerType.MEMORY,
                PromptLayerType.CURRENT_GAME_CONTEXT,
                PromptLayerType.CURRENT_CHAT_BATCH
            ),
            providerRequest.promptLayers().stream().map(PromptLayer::type).toList()
        );
        assertEquals(
            List.of(
                PromptTrust.TRUSTED_INSTRUCTION,
                PromptTrust.TRUSTED_INSTRUCTION,
                PromptTrust.TRUSTED_INSTRUCTION,
                PromptTrust.UNTRUSTED_DATA,
                PromptTrust.UNTRUSTED_DATA,
                PromptTrust.UNTRUSTED_DATA,
                PromptTrust.UNTRUSTED_DATA
            ),
            providerRequest.promptLayers().stream().map(PromptLayer::trust).toList()
        );
        assertEquals(
            List.of("worldmind.built-in-safety-policy", "worldmind.participation-protocol.v1"),
            layer(providerRequest, PromptLayerType.BUILT_IN_SAFETY_POLICY).fragments().stream()
                .map(PromptFragment::source)
                .toList()
        );
        assertTrue(layer(providerRequest, PromptLayerType.BUILT_IN_SAFETY_POLICY).fragments().get(1).content()
            .contains("A batch containing an EXACT addressing signal must return DIRECT_REPLY."));
        assertEquals(
            List.of(new PromptFragment("profile.administrator-rules", "Do not impersonate administrators.")),
            layer(providerRequest, PromptLayerType.ADMINISTRATOR_RULES).fragments()
        );
        assertEquals(
            List.of(new PromptFragment("profile.persona", """
                characterName: Aster
                persona: A thoughtful guide.
                responseStyle: calm
                responseLengthLimit: 280 characters""")),
            layer(providerRequest, PromptLayerType.PERSONA).fragments()
        );
        assertEquals(
            List.of(new PromptFragment("lore/setting.md", "The old observatory watches the valley.")),
            layer(providerRequest, PromptLayerType.LORE).fragments()
        );
        assertTrue(layer(providerRequest, PromptLayerType.MEMORY).fragments().isEmpty());
        assertEquals(
            List.of(new PromptFragment(SELECTED_CONTEXT.source(), SELECTED_CONTEXT.content())),
            layer(providerRequest, PromptLayerType.CURRENT_GAME_CONTEXT).fragments()
        );
        assertEquals(
            List.of(
                new PromptFragment("public-chat-message.sequence-1", """
                    visiblePlayerName: Mira
                    addressingSignal: NONE
                    message: The road is dark."""),
                new PromptFragment("public-chat-message.sequence-2", """
                    visiblePlayerName: Jon
                    addressingSignal: EXACT
                    message: Aster, can you help?""")
            ),
            layer(providerRequest, PromptLayerType.CURRENT_CHAT_BATCH).fragments()
        );
        String visible = providerVisibleText(providerRequest);
        assertFalse(visible.contains(MIRA_ID.toString()));
        assertFalse(visible.contains(JON_ID.toString()));
        assertFalse(visible.contains(WORLD_ID.stableId()));
        assertFalse(visible.contains("env:WORLDMIND_ACCEPTANCE_KEY"));
        assertFalse(visible.contains("ADDRESSING_SIGNAL"));
        assertFalse(visible.contains("capturedAt"));
        assertFalse(visible.contains("in-flight"));
        assertFalse(visible.contains("old-message-context"));
        assertEquals(Instant.EPOCH.plus(Duration.ofMinutes(5)), scenario.clock().instant());
        assertFalse(outcome.toCompletableFuture().isDone());

        scenario.serverScheduler().runUntilIdle();

        DirectReply response = assertInstanceOf(DirectReply.class, outcome.toCompletableFuture().join());
        assertEquals("The lanterns are lit.", response.text());
    }

    @Test
    void preservesTheTrustHierarchyWhenOnlyCharacterInputsOrCurrentBatchChange() {
        WorldmindProfile baselineProfile = profile("Keep the peace.", "A thoughtful guide.", lore(), "calm", 280);
        ProviderRequest baseline = providerRequest(baselineProfile, batch(ChatBatchSealReason.MAXIMUM_WAIT, List.of(
            message(1, MIRA_ID, "Mira", "How is the valley today?", AddressingSignal.NONE)
        )));

        assertOnlyLayerChanged(
            baseline,
            providerRequest(profile("Protect player privacy.", "A thoughtful guide.", lore(), "calm", 280), singleAmbientBatch("How is the valley today?")),
            PromptLayerType.ADMINISTRATOR_RULES
        );
        assertOnlyLayerChanged(
            baseline,
            providerRequest(profile("Keep the peace.", "A practical scout.", lore(), "calm", 280), singleAmbientBatch("How is the valley today?")),
            PromptLayerType.PERSONA
        );
        assertOnlyLayerChanged(
            baseline,
            providerRequest(
                profile(
                    "Keep the peace.",
                    "A thoughtful guide.",
                    List.of(new LoreMaterial("lore/setting.md", "The river crosses a cedar forest.")),
                    "calm",
                    280
                ),
                singleAmbientBatch("How is the valley today?")
            ),
            PromptLayerType.LORE
        );
        assertOnlyLayerChanged(
            baseline,
            providerRequest(baselineProfile, singleAmbientBatch("Where can I find shelter?")),
            PromptLayerType.CURRENT_CHAT_BATCH
        );
    }

    @Test
    void keepsPresentationOnlyChatNameColorOutOfTheProviderRequest() {
        WorldmindProfile defaultProfile = profile("Keep the peace.", "A thoughtful guide.", lore(), "calm", 280);
        WorldmindProfile coloredProfile = new WorldmindProfile(
            WorldmindProfile.V1_SCHEMA_VERSION,
            "Aster",
            "A thoughtful guide.",
            "Keep the peace.",
            lore(),
            "calm",
            new ResponseLengthLimit(280),
            ChatNameColor.GOLD
        );

        assertEquals(
            providerRequest(defaultProfile, singleAmbientBatch("The bridge is wet.")),
            providerRequest(coloredProfile, singleAmbientBatch("The bridge is wet."))
        );
    }

    @Test
    void requiresADirectReplyForAnExactBatch() {
        assertOutcomeForExactBatch(scenario -> scenario.languageModel().willDirectReplyWith("Yes, Mira."), DirectReply.class);
        assertRefusalForExactBatch(scenario -> scenario.languageModel().willRemainSilent(), RefusalCode.INVALID_PROVIDER_RESPONSE);
        assertRefusalForExactBatch(scenario -> scenario.languageModel().willAmbientReplyWith("The bells are ringing."), RefusalCode.INVALID_PROVIDER_RESPONSE);
    }

    @Test
    void letsLikelyAddressingRemainContextual() {
        WorldmindAcceptanceScenario directScenario = WorldmindTestkit.scenario();
        directScenario.languageModel().willDirectReplyWith("I heard you.");
        ConversationOutcome direct = complete(directScenario, likelyBatch("People call you Aster, right?"));
        assertEquals(new DirectReply("I heard you."), direct);

        WorldmindAcceptanceScenario silenceScenario = WorldmindTestkit.scenario();
        silenceScenario.languageModel().willRemainSilent();
        ConversationOutcome silence = complete(silenceScenario, likelyBatch("Aster is a fine name for a horse."));
        assertEquals(DeliberateSilence.INSTANCE, silence);
    }

    @Test
    void allowsRelevantAmbientRepliesAndDeliberateAmbientSilenceWithoutAQuota() {
        WorldmindAcceptanceScenario ambientScenario = WorldmindTestkit.scenario();
        ambientScenario.languageModel().willAmbientReplyWith("The rain will make the bridge slick.");
        ConversationOutcome ambient = complete(ambientScenario, singleAmbientBatch("The bridge is wet."));
        assertEquals(new AmbientReply("The rain will make the bridge slick."), ambient);

        WorldmindAcceptanceScenario silenceScenario = WorldmindTestkit.scenario();
        silenceScenario.languageModel().willRemainSilent();
        ConversationOutcome silence = complete(silenceScenario, singleAmbientBatch("I have ten cobblestone."));
        assertEquals(DeliberateSilence.INSTANCE, silence);
    }

    @Test
    void mapsProviderFailuresAndMalformedParticipationDecisionsWithoutExceptionalServerCompletion() {
        assertRefusal(RefusalCode.PROVIDER_UNAVAILABLE, scenario ->
            scenario.languageModel().willRefuseWith(RefusalCode.PROVIDER_UNAVAILABLE)
        );
        assertRefusal(RefusalCode.PROVIDER_UNAVAILABLE, scenario ->
            scenario.languageModel().willFailWith(new IllegalStateException("deterministic provider failure"))
        );
        assertRefusal(RefusalCode.PROVIDER_UNAVAILABLE, scenario -> scenario.languageModel().willReturnNoCompletionStage());
        assertRefusal(RefusalCode.PROVIDER_UNAVAILABLE, scenario -> scenario.languageModel().willCompleteWithNoResult());
        assertRefusal(RefusalCode.INVALID_PROVIDER_RESPONSE, scenario ->
            scenario.languageModel().willRefuseWith(RefusalCode.INVALID_PROVIDER_RESPONSE)
        );
        assertRefusal(RefusalCode.EMPTY_RESPONSE, scenario -> scenario.languageModel().willRespondWith(" \r\n\t "));
        assertRefusal(RefusalCode.EMPTY_RESPONSE, scenario -> scenario.languageModel().willRespondWith("DIRECT_REPLY\r\n  "));
        assertRefusal(RefusalCode.EMPTY_RESPONSE, scenario -> scenario.languageModel().willRespondWith("AMBIENT_REPLY\n\t"));
        assertRefusal(RefusalCode.INVALID_PROVIDER_RESPONSE, scenario -> scenario.languageModel().willRespondWith("reply without a token"));
        assertRefusal(RefusalCode.INVALID_PROVIDER_RESPONSE, scenario -> scenario.languageModel().willRespondWith("DIRECT_REPLY explain"));
        assertRefusal(RefusalCode.INVALID_PROVIDER_RESPONSE, scenario -> scenario.languageModel().willRespondWith("{\"decision\":\"SILENT\"}"));
        assertRefusal(RefusalCode.INVALID_PROVIDER_RESPONSE, scenario -> scenario.languageModel().willRespondWith("SILENT\nextra"));
        assertRefusal(RefusalCode.INVALID_PROVIDER_RESPONSE, scenario -> scenario.languageModel().willRespondWith("```\nSILENT\n```"));

        WorldmindAcceptanceScenario normalizedScenario = WorldmindTestkit.scenario();
        normalizedScenario.languageModel().willRespondWith(" \r\nDIRECT_REPLY\r\n  Still here.\r\n ");
        ConversationOutcome normalized = complete(normalizedScenario, likelyBatch("Aster?"));
        assertEquals(new DirectReply("Still here."), normalized);
    }

    @Test
    void schedulesEvenEarlyIncompatibilityAndSilentDecisionsOnTheServerScheduler() {
        WorldmindAcceptanceScenario incompatibleScenario = WorldmindTestkit.scenario();
        CompletionStage<ConversationOutcome> incompatible = incompatibleScenario.submit(
            singleAmbientBatch("Can you help?"),
            configuration(profile("Keep the peace.", "A thoughtful guide.", lore(), "calm", 280)),
            new ProviderCapabilities(false)
        );
        assertTrue(incompatibleScenario.languageModel().receivedRequests().isEmpty());
        assertFalse(incompatible.toCompletableFuture().isDone());
        incompatibleScenario.serverScheduler().runUntilIdle();
        assertEquals(new ConversationRefusal(RefusalCode.PROVIDER_INCOMPATIBLE), incompatible.toCompletableFuture().join());

        WorldmindAcceptanceScenario silentScenario = WorldmindTestkit.scenario();
        silentScenario.languageModel().willRemainSilent();
        CompletionStage<ConversationOutcome> silent = silentScenario.submit(
            likelyBatch("Aster is mentioned."),
            configuration(profile("Keep the peace.", "A thoughtful guide.", lore(), "calm", 280)),
            SYSTEM_INSTRUCTION_PROVIDER
        );
        assertFalse(silent.toCompletableFuture().isDone());
        silentScenario.serverScheduler().runUntilIdle();
        assertEquals(DeliberateSilence.INSTANCE, silent.toCompletableFuture().join());
    }

    private ProviderRequest providerRequest(WorldmindProfile profile, SealedChatBatch chatBatch) {
        WorldmindAcceptanceScenario scenario = WorldmindTestkit.scenario();
        scenario.submit(chatBatch, configuration(profile), SYSTEM_INSTRUCTION_PROVIDER);
        return scenario.languageModel().onlyReceivedRequest();
    }

    private void assertOutcomeForExactBatch(
        java.util.function.Consumer<WorldmindAcceptanceScenario> arrangeProvider,
        Class<? extends ConversationOutcome> expectedType
    ) {
        WorldmindAcceptanceScenario scenario = WorldmindTestkit.scenario();
        arrangeProvider.accept(scenario);
        ConversationOutcome outcome = complete(scenario, exactBatch());
        assertInstanceOf(expectedType, outcome);
    }

    private void assertRefusalForExactBatch(
        java.util.function.Consumer<WorldmindAcceptanceScenario> arrangeProvider,
        RefusalCode expectedCode
    ) {
        WorldmindAcceptanceScenario scenario = WorldmindTestkit.scenario();
        arrangeProvider.accept(scenario);
        ConversationRefusal refusal = assertInstanceOf(ConversationRefusal.class, complete(scenario, exactBatch()));
        assertEquals(expectedCode, refusal.code());
    }

    private void assertRefusal(
        RefusalCode expectedCode,
        java.util.function.Consumer<WorldmindAcceptanceScenario> arrangeProvider
    ) {
        WorldmindAcceptanceScenario scenario = WorldmindTestkit.scenario();
        arrangeProvider.accept(scenario);
        CompletionStage<ConversationOutcome> outcome = scenario.submit(
            likelyBatch("Can you help?"),
            configuration(profile("Keep the peace.", "A thoughtful guide.", lore(), "calm", 280)),
            SYSTEM_INSTRUCTION_PROVIDER
        );
        assertFalse(outcome.toCompletableFuture().isCompletedExceptionally());
        scenario.serverScheduler().runUntilIdle();

        ConversationRefusal refusal = assertInstanceOf(ConversationRefusal.class, outcome.toCompletableFuture().join());
        assertEquals(expectedCode, refusal.code());
    }

    private ConversationOutcome complete(WorldmindAcceptanceScenario scenario, SealedChatBatch chatBatch) {
        CompletionStage<ConversationOutcome> outcome = scenario.submit(
            chatBatch,
            configuration(profile("Keep the peace.", "A thoughtful guide.", lore(), "calm", 280)),
            SYSTEM_INSTRUCTION_PROVIDER
        );
        assertFalse(outcome.toCompletableFuture().isDone());
        scenario.serverScheduler().runUntilIdle();
        return outcome.toCompletableFuture().join();
    }

    private SealedChatBatch exactBatch() {
        return batch(ChatBatchSealReason.ADDRESSING_SIGNAL, List.of(
            message(1, MIRA_ID, "Mira", "Aster!", AddressingSignal.EXACT)
        ));
    }

    private SealedChatBatch likelyBatch(String text) {
        return batch(ChatBatchSealReason.ADDRESSING_SIGNAL, List.of(
            message(1, MIRA_ID, "Mira", text, AddressingSignal.LIKELY)
        ));
    }

    private SealedChatBatch singleAmbientBatch(String text) {
        return batch(ChatBatchSealReason.MAXIMUM_WAIT, List.of(
            message(1, MIRA_ID, "Mira", text, AddressingSignal.NONE)
        ));
    }

    private SealedChatBatch batch(ChatBatchSealReason sealReason, List<ObservedPublicChatMessage> messages) {
        return new SealedChatBatch(WORLD_ID, messages, sealReason, List.of(SELECTED_CONTEXT));
    }

    private ObservedPublicChatMessage message(
        long sequence,
        UUID playerId,
        String playerName,
        String text,
        AddressingSignal addressingSignal
    ) {
        return new ObservedPublicChatMessage(
            sequence,
            new ServerRequester(playerId, playerName),
            text,
            addressingSignal,
            Instant.EPOCH.plusSeconds(sequence),
            List.of(new UntrustedContext("old-message-context", "must not enter the provider request"))
        );
    }

    private void assertOnlyLayerChanged(
        ProviderRequest baseline,
        ProviderRequest changed,
        PromptLayerType expectedChangedLayer
    ) {
        assertEquals(baseline.model(), changed.model());
        assertEquals(baseline.generationParameters(), changed.generationParameters());
        assertEquals(baseline.promptLayers().size(), changed.promptLayers().size());
        for (int index = 0; index < baseline.promptLayers().size(); index++) {
            PromptLayer originalLayer = baseline.promptLayers().get(index);
            PromptLayer changedLayer = changed.promptLayers().get(index);
            assertEquals(originalLayer.type(), changedLayer.type());
            if (originalLayer.type() == expectedChangedLayer) {
                assertNotEquals(originalLayer, changedLayer);
            } else {
                assertEquals(originalLayer, changedLayer);
            }
        }
    }

    private PromptLayer layer(ProviderRequest request, PromptLayerType type) {
        return request.promptLayers().stream()
            .filter(layer -> layer.type() == type)
            .findFirst()
            .orElseThrow();
    }

    private String providerVisibleText(ProviderRequest request) {
        return request.promptLayers().stream()
            .flatMap(layer -> layer.fragments().stream())
            .flatMap(fragment -> java.util.stream.Stream.of(fragment.source(), fragment.content()))
            .collect(Collectors.joining("\n"));
    }

    private ValidatedWorldmindConfiguration configuration(WorldmindProfile profile) {
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
                new ChatBatchingConfiguration(8, 5_000, 4_000)
            ),
            profile
        );
    }

    private WorldmindProfile profile(
        String administratorRules,
        String persona,
        List<LoreMaterial> loreMaterials,
        String responseStyle,
        int responseLengthLimit
    ) {
        return new WorldmindProfile(
            WorldmindProfile.V1_SCHEMA_VERSION,
            "Aster",
            persona,
            administratorRules,
            loreMaterials,
            responseStyle,
            new ResponseLengthLimit(responseLengthLimit)
        );
    }

    private List<LoreMaterial> lore() {
        return List.of(new LoreMaterial("lore/setting.md", "The old observatory watches the valley."));
    }
}
