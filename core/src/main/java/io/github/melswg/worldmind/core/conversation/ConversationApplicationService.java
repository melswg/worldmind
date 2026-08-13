package io.github.melswg.worldmind.core.conversation;

import io.github.melswg.worldmind.core.memory.MemoryRetrievalRequest;
import io.github.melswg.worldmind.core.memory.RetrievedMemoryContext;
import io.github.melswg.worldmind.core.memory.WorldMemoryRepository;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/**
 * The single core boundary that accepts a normalized server request and returns
 * its outcome on the server scheduler.
 */
public final class ConversationApplicationService {
    private final LanguageModel languageModel;
    private final Executor serverScheduler;
    private final ConversationPromptBuilder promptBuilder;
    private final WorldMemoryRepository memoryRepository;

    public ConversationApplicationService(LanguageModel languageModel, Executor serverScheduler) {
        this(languageModel, serverScheduler, WorldMemoryRepository.empty());
    }

    public ConversationApplicationService(
        LanguageModel languageModel,
        Executor serverScheduler,
        WorldMemoryRepository memoryRepository
    ) {
        this.languageModel = Objects.requireNonNull(languageModel, "languageModel");
        this.serverScheduler = Objects.requireNonNull(serverScheduler, "serverScheduler");
        this.memoryRepository = Objects.requireNonNull(memoryRepository, "memoryRepository");
        this.promptBuilder = new ConversationPromptBuilder();
    }

    public CompletionStage<ConversationOutcome> handle(NormalizedServerRequest request) {
        return handleTracked(request).thenApply(ConversationExecution::outcome);
    }

    /**
     * Runs the same conversation path while retaining the auditable fact of
     * whether the language-model boundary was reached.
     */
    public CompletionStage<ConversationExecution> handleTracked(NormalizedServerRequest request) {
        Objects.requireNonNull(request, "request");

        if (!request.providerCapabilities().supportsSystemInstructions()) {
            return scheduled(new ConversationExecution(new ConversationRefusal(RefusalCode.PROVIDER_INCOMPATIBLE), false));
        }
        if (memoryRepository == WorldMemoryRepository.empty()) {
            return handleWithMemory(request, RetrievedMemoryContext.empty());
        }

        CompletionStage<RetrievedMemoryContext> recalled;
        try {
            recalled = memoryRepository.retrievePublic(new MemoryRetrievalRequest(request.chatBatch()));
            if (recalled == null) {
                recalled = CompletableFuture.failedFuture(new IllegalStateException("Memory repository returned no recall stage."));
            }
        } catch (RuntimeException failure) {
            recalled = CompletableFuture.failedFuture(failure);
        }

        return recalled.<CompletionStage<ConversationExecution>>handleAsync((memory, failure) -> {
            if (failure != null || memory == null) {
                return CompletableFuture.completedFuture(
                    new ConversationExecution(new ConversationRefusal(RefusalCode.MEMORY_UNAVAILABLE), false)
                );
            }
            return handleWithMemory(request, memory);
        }, serverScheduler).thenCompose(stage -> stage);
    }

    private CompletionStage<ConversationExecution> handleWithMemory(NormalizedServerRequest request, RetrievedMemoryContext memory) {
        Optional<ProviderRequest> providerRequest = promptBuilder.build(request, memory);
        if (providerRequest.isEmpty()) {
            return scheduled(new ConversationExecution(new ConversationRefusal(RefusalCode.PROMPT_BUDGET_EXCEEDED), false));
        }

        CompletionStage<LanguageModelResult> completion;
        try {
            completion = languageModel.complete(providerRequest.orElseThrow());
            if (completion == null) {
                completion = CompletableFuture.failedFuture(
                    new IllegalStateException("Language model returned no completion stage.")
                );
            }
        } catch (RuntimeException failure) {
            completion = CompletableFuture.failedFuture(failure);
        }

        return completion.handleAsync(
            (result, failure) -> new ConversationExecution(toOutcome(request, result, failure), true),
            serverScheduler
        );
    }

    private <T> CompletionStage<T> scheduled(T outcome) {
        CompletableFuture<T> scheduledOutcome = new CompletableFuture<>();
        serverScheduler.execute(() -> scheduledOutcome.complete(outcome));
        return scheduledOutcome;
    }

    private ConversationOutcome toOutcome(NormalizedServerRequest request, LanguageModelResult result, Throwable failure) {
        if (failure != null || result == null) {
            return new ConversationRefusal(RefusalCode.PROVIDER_UNAVAILABLE);
        }

        if (result instanceof ProviderRefusal refusal) {
            return new ConversationRefusal(refusal.code());
        }

        ProviderResponse response = (ProviderResponse) result;
        ConversationOutcome outcome = ParticipationProtocol.decode(
            response.text(),
            request.validatedConfiguration().profile().responseLengthLimit()
        );
        if (containsExactAddress(request) && (outcome instanceof AmbientReply || outcome instanceof DeliberateSilence)) {
            return new ConversationRefusal(RefusalCode.INVALID_PROVIDER_RESPONSE);
        }
        return outcome;
    }

    private boolean containsExactAddress(NormalizedServerRequest request) {
        return request.chatBatch().messages().stream()
            .anyMatch(message -> message.addressingSignal() == AddressingSignal.EXACT);
    }
}
