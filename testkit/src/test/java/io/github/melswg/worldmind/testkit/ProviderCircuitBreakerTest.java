package io.github.melswg.worldmind.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melswg.worldmind.core.configuration.GenerationParameters;
import io.github.melswg.worldmind.core.configuration.ProviderCircuitBreakerConfiguration;
import io.github.melswg.worldmind.core.conversation.CircuitBreakingLanguageModel;
import io.github.melswg.worldmind.core.conversation.LanguageModel;
import io.github.melswg.worldmind.core.conversation.ProviderCircuitBreaker;
import io.github.melswg.worldmind.core.conversation.ProviderCircuitState;
import io.github.melswg.worldmind.core.conversation.ProviderFailure;
import io.github.melswg.worldmind.core.conversation.ProviderFailureKind;
import io.github.melswg.worldmind.core.conversation.ProviderRefusal;
import io.github.melswg.worldmind.core.conversation.ProviderRequest;
import io.github.melswg.worldmind.core.conversation.ProviderResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ProviderCircuitBreakerTest {
    @Test
    void opensAfterThresholdAllowsOneProbeAndClosesOnRecovery() {
        ControlledClock clock = ControlledClock.startingAt(Instant.EPOCH);
        AtomicInteger calls = new AtomicInteger();
        LanguageModel delegate = ignored -> CompletableFuture.completedFuture(calls.incrementAndGet() < 3
            ? new ProviderFailure(ProviderFailureKind.HTTP_SERVER_ERROR) : new ProviderResponse("DIRECT_REPLY\nback"));
        ProviderCircuitBreaker breaker = new ProviderCircuitBreaker(new ProviderCircuitBreakerConfiguration(2, 1_000), clock);
        CircuitBreakingLanguageModel guarded = new CircuitBreakingLanguageModel(delegate, delegate, breaker);

        guarded.complete(request()).toCompletableFuture().join();
        guarded.complete(request()).toCompletableFuture().join();
        assertEquals(ProviderCircuitState.OPEN, breaker.snapshot().state());
        assertInstanceOf(ProviderRefusal.class, guarded.complete(request()).toCompletableFuture().join());
        assertEquals(2, calls.get(), "open circuit must not invoke HTTP");

        clock.advanceBy(Duration.ofMillis(1_000));
        assertEquals("DIRECT_REPLY\nback", assertInstanceOf(ProviderResponse.class,
            guarded.complete(request()).toCompletableFuture().join()).text());
        assertEquals(ProviderCircuitState.CLOSED, breaker.snapshot().state());
        assertFalse(breaker.snapshot().probeInFlight());
        assertTrue(breaker.snapshot().cooldownUntil().isEmpty());
    }

    private ProviderRequest request() {
        return new ProviderRequest("test", new GenerationParameters(Optional.empty(), Optional.empty(), Optional.empty()), List.of(
            new io.github.melswg.worldmind.core.conversation.PromptLayer(
                io.github.melswg.worldmind.core.conversation.PromptLayerType.BUILT_IN_SAFETY_POLICY,
                io.github.melswg.worldmind.core.conversation.PromptTrust.TRUSTED_INSTRUCTION,
                List.of(new io.github.melswg.worldmind.core.conversation.PromptFragment("test", "test"))
            )
        ));
    }
}
