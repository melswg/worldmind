package io.github.melswg.worldmind.testkit;

import io.github.melswg.worldmind.core.conversation.LanguageModel;
import io.github.melswg.worldmind.core.conversation.LanguageModelResult;
import io.github.melswg.worldmind.core.conversation.ProviderRefusal;
import io.github.melswg.worldmind.core.conversation.ProviderRequest;
import io.github.melswg.worldmind.core.conversation.ProviderResponse;
import io.github.melswg.worldmind.core.conversation.RefusalCode;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Deterministic LLM test double. It records the stable provider request rather
 * than an HTTP or JSON representation.
 */
public final class FakeLanguageModel implements LanguageModel {
    private final CopyOnWriteArrayList<ProviderRequest> receivedRequests = new CopyOnWriteArrayList<>();
    private Function<ProviderRequest, CompletionStage<LanguageModelResult>> scenario = request ->
        CompletableFuture.completedFuture(new ProviderRefusal(RefusalCode.PROVIDER_UNAVAILABLE));

    public FakeLanguageModel willRespondWith(String responseText) {
        return willCompleteWith(new ProviderResponse(responseText));
    }

    /** Arranges raw provider outputs in request order for deterministic delivery tests. */
    public FakeLanguageModel willRespondWithSequence(String... responseTexts) {
        Objects.requireNonNull(responseTexts, "responseTexts");
        List<String> responses = List.of(responseTexts.clone());
        if (responses.isEmpty() || responses.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("responseTexts must contain at least one non-null response.");
        }
        AtomicInteger next = new AtomicInteger();
        scenario = request -> {
            int index = next.getAndIncrement();
            if (index >= responses.size()) {
                return CompletableFuture.failedFuture(new IllegalStateException("No fake response remains for the request."));
            }
            return CompletableFuture.completedFuture(new ProviderResponse(responses.get(index)));
        };
        return this;
    }

    /** Arranges a valid protocol direct-reply decision. */
    public FakeLanguageModel willDirectReplyWith(String text) {
        return willRespondWith("DIRECT_REPLY\n" + text);
    }

    /** Arranges a valid protocol ambient-reply decision. */
    public FakeLanguageModel willAmbientReplyWith(String text) {
        return willRespondWith("AMBIENT_REPLY\n" + text);
    }

    /** Arranges a valid protocol decision to remain silent. */
    public FakeLanguageModel willRemainSilent() {
        return willRespondWith("SILENT");
    }

    public FakeLanguageModel willRefuseWith(RefusalCode code) {
        return willCompleteWith(new ProviderRefusal(code));
    }

    public FakeLanguageModel willCompleteWith(LanguageModelResult result) {
        Objects.requireNonNull(result, "result");
        scenario = request -> CompletableFuture.completedFuture(result);
        return this;
    }

    public FakeLanguageModel willFailWith(RuntimeException failure) {
        Objects.requireNonNull(failure, "failure");
        scenario = request -> CompletableFuture.failedFuture(failure);
        return this;
    }

    public FakeLanguageModel willReturnNoCompletionStage() {
        scenario = request -> null;
        return this;
    }

    public FakeLanguageModel willCompleteWithNoResult() {
        scenario = request -> CompletableFuture.completedFuture(null);
        return this;
    }

    public List<ProviderRequest> receivedRequests() {
        return List.copyOf(receivedRequests);
    }

    public ProviderRequest onlyReceivedRequest() {
        List<ProviderRequest> requests = receivedRequests();
        if (requests.size() != 1) {
            throw new IllegalStateException("Expected exactly one provider request but received " + requests.size() + ".");
        }
        return requests.get(0);
    }

    @Override
    public CompletionStage<LanguageModelResult> complete(ProviderRequest request) {
        ProviderRequest recordedRequest = Objects.requireNonNull(request, "request");
        receivedRequests.add(recordedRequest);
        return scenario.apply(recordedRequest);
    }
}
