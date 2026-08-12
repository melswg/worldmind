package io.github.melswg.worldmind.testkit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

/** A manually advanced clock for repeatable time-dependent acceptance scenarios. */
public final class ControlledClock extends Clock {
    private Instant currentInstant;
    private final ZoneId zone;

    public ControlledClock(Instant initialInstant, ZoneId zone) {
        this.currentInstant = Objects.requireNonNull(initialInstant, "initialInstant");
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    public static ControlledClock startingAt(Instant initialInstant) {
        return new ControlledClock(initialInstant, ZoneId.of("UTC"));
    }

    public void advanceBy(Duration duration) {
        currentInstant = currentInstant.plus(Objects.requireNonNull(duration, "duration"));
    }

    public void set(Instant instant) {
        currentInstant = Objects.requireNonNull(instant, "instant");
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId requestedZone) {
        return new ControlledClock(currentInstant, requestedZone);
    }

    @Override
    public Instant instant() {
        return currentInstant;
    }
}
