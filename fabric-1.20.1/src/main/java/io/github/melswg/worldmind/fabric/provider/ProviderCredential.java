package io.github.melswg.worldmind.fabric.provider;

import io.github.melswg.worldmind.core.configuration.SecretRedactionPolicy;
import java.util.Objects;

/** Authorization material usable only by the transport; stringification is always redacted. */
public final class ProviderCredential {
    private final String material;

    public ProviderCredential(String material) {
        this.material = Objects.requireNonNull(material, "material");
        if (material.isBlank()) throw new IllegalArgumentException("credential must not be blank.");
        SecretRedactionPolicy.register(material);
    }

    String authorizationValue() { return material; }

    @Override public String toString() { return "[REDACTED]"; }
}
