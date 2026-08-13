package io.github.melswg.worldmind.gamecontext.internal;

import io.github.melswg.worldmind.api.gamecontext.v1.GameContextLimits;
import java.time.Duration;
import java.util.Objects;

/** Internal bounded-worker policy; production uses the fixed v0.1 public timeout. */
public final class GameContextInvocationPolicy {
    private final Duration timeout;

    public static GameContextInvocationPolicy v01() {
        return new GameContextInvocationPolicy(Duration.ofMillis(GameContextLimits.CALLBACK_TIMEOUT_MILLIS));
    }

    GameContextInvocationPolicy(Duration timeout) {
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("timeout must be positive.");
    }

    Duration timeout() {
        return timeout;
    }
}
