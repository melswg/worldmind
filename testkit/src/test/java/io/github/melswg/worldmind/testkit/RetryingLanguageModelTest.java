package io.github.melswg.worldmind.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melswg.worldmind.core.configuration.GenerationParameters;
import io.github.melswg.worldmind.core.configuration.ProviderRetryConfiguration;
import io.github.melswg.worldmind.core.conversation.LanguageModel;
import io.github.melswg.worldmind.core.conversation.LanguageModelResult;
import io.github.melswg.worldmind.core.conversation.ProviderFailure;
import io.github.melswg.worldmind.core.conversation.ProviderFailureKind;
import io.github.melswg.worldmind.core.conversation.ProviderRequest;
import io.github.melswg.worldmind.core.conversation.ProviderResponse;
import io.github.melswg.worldmind.core.conversation.RetryingLanguageModel;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RetryingLanguageModelTest {
    @Test
    void retriesOnlyTransientFailuresAtControlledBackoffAndCompletesOnce() {
        ControlledClock clock = ControlledClock.startingAt(Instant.EPOCH);
        DeterministicScheduler scheduler = new DeterministicScheduler(clock);
        AtomicInteger attempts = new AtomicInteger();
        LanguageModel delegate = ignored -> CompletableFuture.completedFuture(switch (attempts.incrementAndGet()) {
            case 1 -> new ProviderFailure(ProviderFailureKind.HTTP_RATE_LIMITED);
            case 2 -> new ProviderFailure(ProviderFailureKind.HTTP_SERVER_ERROR);
            default -> new ProviderResponse("DIRECT_REPLY\nrecovered");
        });
        RetryingLanguageModel retries = new RetryingLanguageModel(
            delegate, new ProviderRetryConfiguration(3, 100, 1_000, 0.0), scheduler, () -> 0.0
        );

        CompletionStage<LanguageModelResult> result = retries.complete(request());
        assertEquals(1, attempts.get());
        assertFalse(result.toCompletableFuture().isDone());
        clock.advanceBy(Duration.ofMillis(99));
        scheduler.runDueTasks();
        assertEquals(1, attempts.get());
        clock.advanceBy(Duration.ofMillis(1));
        scheduler.runDueTasks();
        assertEquals(2, attempts.get());
        clock.advanceBy(Duration.ofMillis(200));
        scheduler.runDueTasks();
        assertEquals(3, attempts.get());
        assertEquals("DIRECT_REPLY\nrecovered", assertInstanceOf(ProviderResponse.class, result.toCompletableFuture().join()).text());
    }

    @Test
    void doesNotRetryPermanentFailuresAndCancellationCancelsPendingBackoff() {
        ControlledClock clock = ControlledClock.startingAt(Instant.EPOCH);
        DeterministicScheduler scheduler = new DeterministicScheduler(clock);
        AtomicInteger permanentCalls = new AtomicInteger();
        RetryingLanguageModel permanent = new RetryingLanguageModel(
            ignored -> {
                permanentCalls.incrementAndGet();
                return CompletableFuture.completedFuture(new ProviderFailure(ProviderFailureKind.MALFORMED_JSON));
            }, new ProviderRetryConfiguration(3, 100, 1_000, 0.0), scheduler, () -> 0.0
        );
        assertEquals(ProviderFailureKind.MALFORMED_JSON,
            assertInstanceOf(ProviderFailure.class, permanent.complete(request()).toCompletableFuture().join()).kind());
        assertEquals(1, permanentCalls.get());

        AtomicInteger transientCalls = new AtomicInteger();
        RetryingLanguageModel transientModel = new RetryingLanguageModel(
            ignored -> {
                transientCalls.incrementAndGet();
                return CompletableFuture.completedFuture(new ProviderFailure(ProviderFailureKind.CONNECTION_FAILURE));
            }, new ProviderRetryConfiguration(3, 100, 1_000, 0.0), scheduler, () -> 0.0
        );
        CompletableFuture<LanguageModelResult> cancelled = transientModel.complete(request()).toCompletableFuture();
        assertTrue(cancelled.cancel(true));
        clock.advanceBy(Duration.ofSeconds(1));
        scheduler.runDueTasks();
        assertEquals(1, transientCalls.get());
    }

    private ProviderRequest request() {
        return new ProviderRequest("test", new GenerationParameters(Optional.empty(), Optional.empty(), Optional.empty()),
            List.of(new io.github.melswg.worldmind.core.conversation.PromptLayer(
                io.github.melswg.worldmind.core.conversation.PromptLayerType.BUILT_IN_SAFETY_POLICY,
                io.github.melswg.worldmind.core.conversation.PromptTrust.TRUSTED_INSTRUCTION, List.of(
                    new io.github.melswg.worldmind.core.conversation.PromptFragment("test", "test")
                )
            )));
    }
}
