package io.github.melswg.worldmind.core.conversation;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Rejects open circuits before HTTP and makes half-open recovery use one attempt only. */
public final class CircuitBreakingLanguageModel implements LanguageModel {
    private final LanguageModel retrying;
    private final LanguageModel singleAttempt;
    private final ProviderCircuitBreaker breaker;

    public CircuitBreakingLanguageModel(LanguageModel retrying, LanguageModel singleAttempt, ProviderCircuitBreaker breaker) {
        this.retrying = Objects.requireNonNull(retrying, "retrying");
        this.singleAttempt = Objects.requireNonNull(singleAttempt, "singleAttempt");
        this.breaker = Objects.requireNonNull(breaker, "breaker");
    }

    @Override public CompletionStage<LanguageModelResult> complete(ProviderRequest request) {
        return breaker.acquire().<CompletionStage<LanguageModelResult>>map(permit -> {
            LanguageModel delegate = permit.probe() ? singleAttempt : retrying;
            CompletionStage<LanguageModelResult> stage;
            try { stage = delegate.complete(request); } catch (RuntimeException failure) {
                stage = CompletableFuture.completedFuture(new ProviderFailure(ProviderFailureKind.CONNECTION_FAILURE));
            }
            if (stage == null) stage = CompletableFuture.completedFuture(new ProviderFailure(ProviderFailureKind.CONNECTION_FAILURE));
            return stage.handle((result, failure) -> {
                LanguageModelResult resolved = failure == null && result != null ? result
                    : new ProviderFailure(ProviderFailureKind.CONNECTION_FAILURE);
                breaker.record(permit, resolved);
                return resolved;
            });
        }).orElseGet(() -> CompletableFuture.completedFuture(new ProviderRefusal(RefusalCode.PROVIDER_CIRCUIT_OPEN)));
    }
}
