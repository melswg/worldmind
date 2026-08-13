package io.github.melswg.worldmind.core.conversation;

import io.github.melswg.worldmind.core.configuration.ProviderCircuitBreakerConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Thread-safe provider-scoped breaker with a single recovery probe. */
public final class ProviderCircuitBreaker {
    private final ProviderCircuitBreakerConfiguration configuration;
    private final Clock clock;
    private ProviderCircuitState state = ProviderCircuitState.CLOSED;
    private int failures;
    private Instant cooldownUntil;
    private boolean probeInFlight;

    public ProviderCircuitBreaker(ProviderCircuitBreakerConfiguration configuration, Clock clock) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Returns a permit or empty when provider HTTP must not be called. */
    public synchronized Optional<Permit> acquire() {
        Instant now = clock.instant();
        if (state == ProviderCircuitState.OPEN && !now.isBefore(cooldownUntil)) state = ProviderCircuitState.HALF_OPEN;
        if (state == ProviderCircuitState.OPEN || (state == ProviderCircuitState.HALF_OPEN && probeInFlight)) return Optional.empty();
        boolean probe = state == ProviderCircuitState.HALF_OPEN;
        if (probe) probeInFlight = true;
        return Optional.of(new Permit(probe));
    }

    public synchronized void record(Permit permit, LanguageModelResult result) {
        Objects.requireNonNull(permit, "permit");
        Objects.requireNonNull(result, "result");
        if (permit.probe()) probeInFlight = false;
        if (qualifies(result)) {
            if (permit.probe() || ++failures >= configuration.failureThreshold()) open();
            return;
        }
        if (result instanceof ProviderResponse || result instanceof ProviderRefusal) close();
    }

    public synchronized ProviderCircuitSnapshot snapshot() {
        return new ProviderCircuitSnapshot(state, failures, Optional.ofNullable(cooldownUntil), probeInFlight);
    }

    private boolean qualifies(LanguageModelResult result) {
        return result instanceof ProviderFailure failure && switch (failure.kind()) {
            case CONNECTION_FAILURE, TIMEOUT, HTTP_RATE_LIMITED, HTTP_SERVER_ERROR, MALFORMED_JSON,
                MALFORMED_RESPONSE, EMPTY_CONTENT, OVERSIZED_CONTENT -> true;
            default -> false;
        };
    }

    private void open() {
        state = ProviderCircuitState.OPEN;
        cooldownUntil = clock.instant().plusMillis(configuration.cooldownMillis());
        probeInFlight = false;
    }

    private void close() {
        state = ProviderCircuitState.CLOSED;
        failures = 0;
        cooldownUntil = null;
        probeInFlight = false;
    }

    /** Opaque request-local permit; only the issuing breaker may consume it. */
    public record Permit(boolean probe) { }
}
