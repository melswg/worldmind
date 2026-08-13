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
            CompletionStage<LanguageModelResult> delegateStage = stage;
            CompletableFuture<LanguageModelResult> result = new CompletableFuture<>();
            result.whenComplete((ignored, failure) -> {
                if (result.isCancelled()) {
                    breaker.release(permit);
                    try { delegateStage.toCompletableFuture().cancel(true); } catch (RuntimeException ignoredFailure) { }
                }
            });
            stage.whenComplete((value, failure) -> {
                if (result.isDone()) return;
                if (isCancellation(failure) || value instanceof ProviderFailure providerFailure
                    && providerFailure.kind() == ProviderFailureKind.CANCELLED) {
                    result.cancel(false);
                    return;
                }
                LanguageModelResult resolved = failure == null && value != null ? value
                    : new ProviderFailure(ProviderFailureKind.CONNECTION_FAILURE);
                breaker.record(permit, resolved);
                result.complete(resolved);
            });
            return result;
        }).orElseGet(() -> CompletableFuture.completedFuture(new ProviderRefusal(RefusalCode.PROVIDER_CIRCUIT_OPEN)));
    }

    private boolean isCancellation(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof java.util.concurrent.CompletionException
            || current instanceof java.util.concurrent.ExecutionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current instanceof java.util.concurrent.CancellationException;
    }
}
