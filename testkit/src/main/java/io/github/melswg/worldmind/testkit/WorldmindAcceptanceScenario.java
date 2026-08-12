package io.github.melswg.worldmind.testkit;

import io.github.melswg.worldmind.core.conversation.ConversationApplicationService;
import io.github.melswg.worldmind.core.conversation.ConversationOutcome;
import io.github.melswg.worldmind.core.conversation.ChatBatchCoordinator;
import io.github.melswg.worldmind.core.conversation.ChatBatchSealReason;
import io.github.melswg.worldmind.core.conversation.LanguageModel;
import io.github.melswg.worldmind.core.conversation.NormalizedServerRequest;
import io.github.melswg.worldmind.core.conversation.ObservedPublicChatMessage;
import io.github.melswg.worldmind.core.conversation.ProviderCapabilities;
import io.github.melswg.worldmind.core.conversation.SealedChatBatch;
import io.github.melswg.worldmind.core.conversation.SealedChatBatchConsumer;
import io.github.melswg.worldmind.core.conversation.UntrustedContext;
import io.github.melswg.worldmind.core.conversation.WorldIdentity;
import io.github.melswg.worldmind.core.configuration.ValidatedWorldmindConfiguration;
import io.github.melswg.worldmind.core.configuration.ChatBatchingConfiguration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * Reusable high-level DSL for server-side Worldmind acceptance scenarios.
 */
public final class WorldmindAcceptanceScenario {
    private final LanguageModel languageModel;
    private final ControlledClock clock = ControlledClock.startingAt(Instant.EPOCH);
    private final DeterministicScheduler serverScheduler = new DeterministicScheduler(clock);
    private final ConversationApplicationService applicationService;

    public WorldmindAcceptanceScenario() {
        this(new FakeLanguageModel());
    }

    public WorldmindAcceptanceScenario(LanguageModel languageModel) {
        this.languageModel = java.util.Objects.requireNonNull(languageModel, "languageModel");
        this.applicationService = new ConversationApplicationService(this.languageModel, serverScheduler);
    }

    public FakeLanguageModel languageModel() {
        if (languageModel instanceof FakeLanguageModel fakeLanguageModel) {
            return fakeLanguageModel;
        }
        throw new IllegalStateException("This scenario uses an injected language model rather than FakeLanguageModel.");
    }

    public DeterministicScheduler serverScheduler() {
        return serverScheduler;
    }

    public ControlledClock clock() {
        return clock;
    }

    public CompletionStage<ConversationOutcome> submit(
        SealedChatBatch chatBatch,
        ValidatedWorldmindConfiguration validatedConfiguration,
        ProviderCapabilities providerCapabilities
    ) {
        return applicationService.handle(normalizedRequest(chatBatch, validatedConfiguration, providerCapabilities));
    }

    /** Creates the Ticket 07 batch boundary without invoking the scenario LLM. */
    public ChatBatchCoordinator chatBatcher(
        ChatBatchingConfiguration configuration,
        String characterName,
        SealedChatBatchConsumer consumer
    ) {
        return new ChatBatchCoordinator(configuration, characterName, clock, serverScheduler, consumer);
    }

    public NormalizedServerRequest normalizedRequest(
        SealedChatBatch chatBatch,
        ValidatedWorldmindConfiguration validatedConfiguration,
        ProviderCapabilities providerCapabilities
    ) {
        return new NormalizedServerRequest(
            chatBatch,
            validatedConfiguration,
            providerCapabilities
        );
    }

    /** Creates an explicit Ticket 07 sealed-batch handoff for decision tests. */
    public SealedChatBatch sealedChatBatch(
        WorldIdentity worldIdentity,
        List<ObservedPublicChatMessage> messages,
        ChatBatchSealReason sealReason,
        List<UntrustedContext> currentContextSnapshot
    ) {
        return new SealedChatBatch(worldIdentity, messages, sealReason, currentContextSnapshot);
    }
}
