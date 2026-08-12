package io.github.melswg.worldmind.core.configuration;

import java.net.URI;
import java.util.Objects;

/**
 * A validated provider endpoint. It is distinct from the provider's model and
 * carries no credential material.
 */
public record ProviderEndpoint(URI uri) {
    public ProviderEndpoint {
        Objects.requireNonNull(uri, "uri");
    }
}
