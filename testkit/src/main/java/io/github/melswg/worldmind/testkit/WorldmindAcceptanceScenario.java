package io.github.melswg.worldmind.testkit;

import io.github.melswg.worldmind.core.conversation.ConversationApplicationService;
import io.github.melswg.worldmind.core.conversation.ConversationOutcome;
import io.github.melswg.worldmind.core.conversation.ConversationRefusal;
import io.github.melswg.worldmind.core.conversation.ChatBatchCoordinator;
import io.github.melswg.worldmind.core.conversation.ChatBatchSealReason;
import io.github.melswg.worldmind.core.conversation.LanguageModel;
import io.github.melswg.worldmind.core.conversation.NormalizedServerRequest;
import io.github.melswg.worldmind.core.conversation.ObservedPublicChatMessage;
import io.github.melswg.worldmind.core.conversation.ProviderCapabilities;
import io.github.melswg.worldmind.core.conversation.RefusalCode;
import io.github.melswg.worldmind.core.conversation.SealedChatBatch;
import io.github.melswg.worldmind.core.conversation.SealedChatBatchConsumer;
import io.github.melswg.worldmind.core.conversation.UntrustedContext;
import io.github.melswg.worldmind.core.conversation.WorldIdentity;
import io.github.melswg.worldmind.core.configuration.ValidatedWorldmindConfiguration;
import io.github.melswg.worldmind.core.configuration.ChatBatchingConfiguration;
import io.github.melswg.worldmind.core.memory.WorldMemoryRepository;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;

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
        this(languageModel, WorldMemoryRepository.empty());
    }

    public WorldmindAcceptanceScenario(LanguageModel languageModel, WorldMemoryRepository memoryRepository) {
        this.languageModel = java.util.Objects.requireNonNull(languageModel, "languageModel");
        this.applicationService = new ConversationApplicationService(
            this.languageModel,
            serverScheduler,
            java.util.Objects.requireNonNull(memoryRepository, "memoryRepository")
        );
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

    /** Exposes the one scenario-owned application service to adapter acceptance tests. */
    public ConversationApplicationService applicationService() {
        return applicationService;
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

    /**
     * Extends the existing batching seam through the one application service to
     * an outcome consumer. The outcome is observed only after the deterministic
     * server scheduler runs.
     */
    public ChatBatchCoordinator decidingChatBatcher(
        ChatBatchingConfiguration batchingConfiguration,
        String characterName,
        ValidatedWorldmindConfiguration validatedConfiguration,
        ProviderCapabilities providerCapabilities,
        BiConsumer<SealedChatBatch, ConversationOutcome> outcomeConsumer
    ) {
        java.util.Objects.requireNonNull(validatedConfiguration, "validatedConfiguration");
        java.util.Objects.requireNonNull(providerCapabilities, "providerCapabilities");
        BiConsumer<SealedChatBatch, ConversationOutcome> consumer = java.util.Objects.requireNonNull(
            outcomeConsumer,
            "outcomeConsumer"
        );
        return chatBatcher(batchingConfiguration, characterName, batch -> applicationService.handle(
            normalizedRequest(batch, validatedConfiguration, providerCapabilities)
        ).handle((outcome, failure) -> {
            consumer.accept(
                batch,
                failure == null && outcome != null
                    ? outcome
                    : new ConversationRefusal(RefusalCode.PROVIDER_UNAVAILABLE)
            );
            return null;
        }));
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
