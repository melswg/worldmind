package io.github.melswg.worldmind.testkit;

import io.github.melswg.worldmind.core.configuration.ExternalSecretReference;
import io.github.melswg.worldmind.core.configuration.SecretAvailability;
import io.github.melswg.worldmind.core.configuration.SecretResolver;
import java.util.Objects;

/** A deterministic availability-only secret boundary for acceptance scenarios. */
public final class FakeSecretResolver implements SecretResolver {
    private SecretAvailability availability = SecretAvailability.AVAILABLE;
    private int resolutionCount;
    private ExternalSecretReference lastReference;

    public FakeSecretResolver willResolveAs(SecretAvailability availability) {
        this.availability = Objects.requireNonNull(availability, "availability");
        return this;
    }

    public int resolutionCount() {
        return resolutionCount;
    }

    public ExternalSecretReference lastReference() {
        return lastReference;
    }

    @Override
    public SecretAvailability check(ExternalSecretReference reference) {
        lastReference = Objects.requireNonNull(reference, "reference");
        resolutionCount++;
        return availability;
    }
}
