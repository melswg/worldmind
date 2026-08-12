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

    public ConversationApplicationService(LanguageModel languageModel, Executor serverScheduler) {
        this.languageModel = Objects.requireNonNull(languageModel, "languageModel");
        this.serverScheduler = Objects.requireNonNull(serverScheduler, "serverScheduler");
    }

    public CompletionStage<ConversationOutcome> handle(NormalizedServerRequest request) {
        Objects.requireNonNull(request, "request");

        CompletionStage<LanguageModelResult> completion;
        try {
            completion = languageModel.complete(request.providerRequest());
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
