package io.github.melswg.worldmind.core.conversation;

import io.github.melswg.worldmind.core.configuration.ProviderRetryConfiguration;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

/** Keeps retry/backoff inside one cancellable language-model invocation. */
public final class RetryingLanguageModel implements LanguageModel {
    private final LanguageModel delegate;
    private final ProviderRetryConfiguration policy;
    private final DelayedScheduler scheduler;
    private final JitterSource jitter;
    private final AtomicInteger activeAttempts = new AtomicInteger();
    private final AtomicInteger waitingBackoff = new AtomicInteger();

    public RetryingLanguageModel(
        LanguageModel delegate,
        ProviderRetryConfiguration policy,
        DelayedScheduler scheduler,
        JitterSource jitter
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.jitter = Objects.requireNonNull(jitter, "jitter");
    }

    /** Safe live accounting for the runtime status surface. */
    public RetrySnapshot snapshot() {
        return new RetrySnapshot(activeAttempts.get(), waitingBackoff.get());
    }

    @Override
    public CompletionStage<LanguageModelResult> complete(ProviderRequest request) {
        Objects.requireNonNull(request, "request");
        RequestLifecycle lifecycle = new RequestLifecycle(request);
        lifecycle.attempt(1);
        return lifecycle.result;
    }

    private final class RequestLifecycle {
        private final ProviderRequest request;
        private final CompletableFuture<LanguageModelResult> result = new CompletableFuture<>();
        private volatile CompletionStage<?> active;
        private volatile ScheduledWork pendingBackoff;
        private volatile boolean backoffCounted;

        private RequestLifecycle(ProviderRequest request) {
            this.request = request;
            result.whenComplete((ignored, failure) -> cancelOwned());
        }

        private void attempt(int number) {
            if (result.isDone()) return;
            activeAttempts.incrementAndGet();
            CompletionStage<LanguageModelResult> stage;
            try {
                stage = delegate.complete(request);
                if (stage == null) throw new IllegalStateException("Language model returned no completion stage.");
            } catch (RuntimeException failure) {
                activeAttempts.decrementAndGet();
                result.complete(new ProviderFailure(ProviderFailureKind.CONNECTION_FAILURE));
                return;
            }
            active = stage;
            stage.whenComplete((value, failure) -> {
                activeAttempts.decrementAndGet();
                if (result.isDone()) return;
                LanguageModelResult resolved = failure == null && value != null
                    ? value : new ProviderFailure(ProviderFailureKind.CONNECTION_FAILURE);
                if (isTransient(resolved) && number < policy.maximumAttempts()) {
                    scheduleRetry(number + 1, number);
                } else {
                    result.complete(resolved);
                }
            });
        }

        private void scheduleRetry(int nextAttempt, int failedAttempt) {
            long delay = delayFor(failedAttempt);
            waitingBackoff.incrementAndGet();
            backoffCounted = true;
            pendingBackoff = scheduler.schedule(Duration.ofMillis(delay), () -> {
                consumeBackoffCount();
                attempt(nextAttempt);
            });
        }

        private long delayFor(int failedAttempt) {
            long base = policy.initialBackoffMillis();
            for (int index = 1; index < failedAttempt && base < policy.maximumBackoffMillis(); index++) {
                base = Math.min(policy.maximumBackoffMillis(), Math.multiplyExact(base, 2));
            }
            long variation = (long) Math.floor(base * policy.jitterRatio());
            double unit = Math.max(-1.0, Math.min(1.0, jitter.nextUnitJitter()));
            return Math.max(0, Math.min(policy.maximumBackoffMillis(), base + Math.round(variation * unit)));
        }

        private boolean isTransient(LanguageModelResult result) {
            return result instanceof ProviderFailure failure && switch (failure.kind()) {
                case CONNECTION_FAILURE, TIMEOUT, HTTP_RATE_LIMITED, HTTP_SERVER_ERROR -> true;
                default -> false;
            };
        }

        private void cancelOwned() {
            ScheduledWork delayed = pendingBackoff;
            if (delayed != null) delayed.cancel();
            consumeBackoffCount();
            CompletionStage<?> stage = active;
            if (stage != null) {
                try { stage.toCompletableFuture().cancel(true); } catch (RuntimeException ignored) { }
            }
        }

        private void consumeBackoffCount() {
            if (backoffCounted) {
                backoffCounted = false;
                waitingBackoff.decrementAndGet();
            }
        }
    }
}
