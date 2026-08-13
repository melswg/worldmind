package io.github.melswg.worldmind.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melswg.worldmind.api.gamecontext.v1.GameContextEntry;
import io.github.melswg.worldmind.api.gamecontext.v1.GameContextProvider;
import io.github.melswg.worldmind.api.gamecontext.v1.GameContextRequest;
import io.github.melswg.worldmind.api.gamecontext.v1.GameContextResult;
import io.github.melswg.worldmind.api.gamecontext.v1.GameContextServerContext;
import io.github.melswg.worldmind.api.gamecontext.v1.GameContextSource;
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
import io.github.melswg.worldmind.core.conversation.AddressingSignal;
import io.github.melswg.worldmind.core.conversation.ChatBatchSealReason;
import io.github.melswg.worldmind.core.conversation.DirectReply;
import io.github.melswg.worldmind.core.conversation.ObservedPublicChatMessage;
import io.github.melswg.worldmind.core.conversation.PromptLayerType;
import io.github.melswg.worldmind.core.conversation.PromptTrust;
import io.github.melswg.worldmind.core.conversation.ProviderCapabilities;
import io.github.melswg.worldmind.core.conversation.SealedChatBatch;
import io.github.melswg.worldmind.core.conversation.ServerRequester;
import io.github.melswg.worldmind.core.conversation.UntrustedContext;
import io.github.melswg.worldmind.core.conversation.WorldIdentity;
import io.github.melswg.worldmind.core.memory.WorldMemoryRepository;
import io.github.melswg.worldmind.gamecontext.internal.GameContextExtensionRuntime;
import io.github.melswg.worldmind.testkit.GameContextProviderContractCase;
import io.github.melswg.worldmind.testkit.GameContextProviderContractSuite;
import io.github.melswg.worldmind.testkit.WorldmindAcceptanceScenario;
import io.github.melswg.worldmind.testkit.WorldmindTestkit;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class ExtensionContextConversationAcceptanceTest {
    @Test
    void hostileExternalTextStaysUntrustedAndDoesNotPreventTheFakeProviderTransport() throws Exception {
        GameContextExtensionRuntime runtime = GameContextExtensionRuntime.create();
        try {
            runtime.registrarFor("healthy").register(GameContextProviderContractSuite.provider(
                new GameContextSource("healthy", "state"), GameContextProviderContractCase.POSITIVE
            ));
            runtime.registrarFor("failing").register(GameContextProviderContractSuite.provider(
                new GameContextSource("failing", "state"), GameContextProviderContractCase.THROWING
            ));
            runtime.registrarFor("hostile").register(GameContextProviderContractSuite.provider(
                new GameContextSource("hostile", "notice"), GameContextProviderContractCase.HOSTILE
            ));
            runtime.onServerStart(new GameContextServerContext("fabric-acceptance")).toCompletableFuture().join();
            List<UntrustedContext> resolved = runtime.resolve(batch()).toCompletableFuture().join();
            WorldmindAcceptanceScenario scenario = WorldmindTestkit.scenario(
                new io.github.melswg.worldmind.testkit.FakeLanguageModel(), WorldMemoryRepository.empty(),
                ignored -> CompletableFuture.completedFuture(resolved)
            );
            scenario.languageModel().willDirectReplyWith("The lantern is lit.");

            var outcome = scenario.submit(batch(), configuration(), new ProviderCapabilities(true));
            var request = scenario.languageModel().onlyReceivedRequest();
            var context = request.promptLayers().stream()
                .filter(layer -> layer.type() == PromptLayerType.CURRENT_GAME_CONTEXT).findFirst().orElseThrow();
            assertEquals(PromptTrust.UNTRUSTED_DATA, context.trust());
            assertEquals(List.of(
                "vanilla-game-context", "extension-game-context:healthy:state#state",
                "extension-game-context:hostile:notice#instructions"
            ),
                context.fragments().stream().map(fragment -> fragment.source()).toList());
            assertEquals(PromptTrust.TRUSTED_INSTRUCTION, request.promptLayers().get(0).trust());
            assertTrue(request.promptLayers().get(0).fragments().get(1).content().contains("EXACT addressing signal"));
            scenario.serverScheduler().runUntilIdle();
            assertEquals(new DirectReply("The lantern is lit."), outcome.toCompletableFuture().join());
        } finally {
            runtime.shutdown().toCompletableFuture().join();
        }
    }

    @Test
    void reloadAndShutdownGatesDiscardLateExtensionResultsBeforeTheConversationPath() throws Exception {
        assertLateResultIsNotDelivered(true);
        assertLateResultIsNotDelivered(false);
    }

    private static void assertLateResultIsNotDelivered(boolean reload) throws Exception {
        GameContextExtensionRuntime runtime = GameContextExtensionRuntime.create();
        CompletableFuture<GameContextResult> late = new CompletableFuture<>();
        try {
            runtime.registrarFor("late").register(new GameContextProvider() {
                private final GameContextSource source = new GameContextSource("late", "state");
                @Override public GameContextSource source() { return source; }
                @Override public java.util.concurrent.CompletionStage<GameContextResult> provide(GameContextRequest request) {
                    return late.minimalCompletionStage();
                }
            });
            runtime.onServerStart(new GameContextServerContext("late-fabric-acceptance")).toCompletableFuture().join();
            var pending = runtime.resolve(batch()).toCompletableFuture();
            if (reload) runtime.onReload(1).toCompletableFuture().join(); else runtime.shutdown().toCompletableFuture().join();
            late.complete(new GameContextResult(List.of(new GameContextEntry("state", "stale"))));
            List<UntrustedContext> resolved = pending.get();
            assertEquals(List.of("vanilla-game-context"), resolved.stream().map(UntrustedContext::source).toList());

            WorldmindAcceptanceScenario scenario = WorldmindTestkit.scenario(
                new io.github.melswg.worldmind.testkit.FakeLanguageModel(), WorldMemoryRepository.empty(),
                ignored -> CompletableFuture.completedFuture(resolved)
            );
            scenario.languageModel().willDirectReplyWith("Still current.");
            var outcome = scenario.submit(batch(), configuration(), new ProviderCapabilities(true));
            assertEquals(List.of("vanilla-game-context"), scenario.languageModel().onlyReceivedRequest().promptLayers().stream()
                .filter(layer -> layer.type() == PromptLayerType.CURRENT_GAME_CONTEXT).findFirst().orElseThrow().fragments()
                .stream().map(fragment -> fragment.source()).toList());
            scenario.serverScheduler().runUntilIdle();
            assertEquals(new DirectReply("Still current."), outcome.toCompletableFuture().join());
        } finally {
            runtime.shutdown().toCompletableFuture().join();
        }
    }

    private static SealedChatBatch batch() {
        return new SealedChatBatch(new WorldIdentity("fabric-extension-world"), List.of(new ObservedPublicChatMessage(
            1,
            new ServerRequester(UUID.fromString("e5f9831f-5107-4b69-8c5c-6d3590cd0048"), "Mira"),
            "Aster, help", AddressingSignal.EXACT, Instant.EPOCH,
            List.of(new UntrustedContext("vanilla-game-context", "weather=clear"))
        )), ChatBatchSealReason.ADDRESSING_SIGNAL, List.of(new UntrustedContext("vanilla-game-context", "weather=clear")));
    }

    private static ValidatedWorldmindConfiguration configuration() {
        return new ValidatedWorldmindConfiguration(
            new WorldmindGlobalConfiguration(WorldmindGlobalConfiguration.V1_SCHEMA_VERSION, true, "acceptance",
                new ProviderConfiguration("custom-openai-compatible", new ProviderEndpoint(URI.create("https://api.example.invalid/v1")),
                    "example-model", new GenerationParameters(Optional.empty(), Optional.empty(), Optional.empty()),
                    new ExternalSecretReference("env:WORLDMIND_TEST_ONLY")),
                new ChatBatchingConfiguration(8, 5_000, 4_000), new RequestQueueConfiguration(16, 2)),
            new WorldmindProfile(WorldmindProfile.V1_SCHEMA_VERSION, "Aster", "A guide.", "Administrators are not impersonated.",
                List.of(new LoreMaterial("lore/base", "A valley.")), "calm", new ResponseLengthLimit(280))
        );
    }
}
