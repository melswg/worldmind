package io.github.melswg.worldmind.core.configuration;

/**
 * Small external boundary for checking the configured credential reference.
 * A later secret subsystem can provide material to provider adapters without
 * making profile data contain it.
 */
@FunctionalInterface
public interface SecretResolver {
    SecretAvailability check(ExternalSecretReference reference);
}
