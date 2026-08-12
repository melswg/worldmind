package io.github.melswg.worldmind.core.conversation;

import java.util.Objects;
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

    public ConversationApplicationService(LanguageModel languageModel, Executor serverScheduler) {
        this.languageModel = Objects.requireNonNull(languageModel, "languageModel");
        this.serverScheduler = Objects.requireNonNull(serverScheduler, "serverScheduler");
        this.promptBuilder = new ConversationPromptBuilder();
    }

    public CompletionStage<ConversationOutcome> handle(NormalizedServerRequest request) {
        Objects.requireNonNull(request, "request");

        if (!request.providerCapabilities().supportsSystemInstructions()) {
            return scheduled(new ConversationRefusal(RefusalCode.PROVIDER_INCOMPATIBLE));
        }

        CompletionStage<LanguageModelResult> completion;
        try {
            completion = languageModel.complete(promptBuilder.build(request));
            if (completion == null) {
                completion = CompletableFuture.failedFuture(
                    new IllegalStateException("Language model returned no completion stage.")
                );
            }
        } catch (RuntimeException failure) {
            completion = CompletableFuture.failedFuture(failure);
        }

        return completion.handleAsync(this::toOutcome, serverScheduler);
    }

    private CompletionStage<ConversationOutcome> scheduled(ConversationOutcome outcome) {
        CompletableFuture<ConversationOutcome> scheduledOutcome = new CompletableFuture<>();
        serverScheduler.execute(() -> scheduledOutcome.complete(outcome));
        return scheduledOutcome;
    }

    private ConversationOutcome toOutcome(LanguageModelResult result, Throwable failure) {
        if (failure != null || result == null) {
            return new ConversationRefusal(RefusalCode.PROVIDER_UNAVAILABLE);
        }

        if (result instanceof ProviderRefusal refusal) {
            return new ConversationRefusal(refusal.code());
        }

        ProviderResponse response = (ProviderResponse) result;
        return SafeServerResponse.fromUntrustedModelText(response.text())
            .<ConversationOutcome>map(safeResponse -> safeResponse)
            .orElseGet(() -> new ConversationRefusal(RefusalCode.EMPTY_RESPONSE));
    }
}
