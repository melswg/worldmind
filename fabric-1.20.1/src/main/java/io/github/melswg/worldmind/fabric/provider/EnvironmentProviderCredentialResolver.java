package io.github.melswg.worldmind.fabric.provider;

import io.github.melswg.worldmind.core.configuration.ExternalSecretReference;
import io.github.melswg.worldmind.core.configuration.SecretAvailability;
import io.github.melswg.worldmind.core.configuration.SecretRedactionPolicy;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads the v1 {@code env:NAME} credential reference without exposing it to core. */
public final class EnvironmentProviderCredentialResolver implements ProviderCredentialResolver {
    private static final Pattern ENV_REFERENCE = Pattern.compile("env:([A-Za-z_][A-Za-z0-9_]*)");

    private final Function<String, String> environment;

    public EnvironmentProviderCredentialResolver() {
        this(System::getenv);
    }

    EnvironmentProviderCredentialResolver(Function<String, String> environment) {
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    @Override
    public SecretAvailability check(ExternalSecretReference reference) {
        Matcher matcher = matcher(reference);
        if (matcher == null) {
            return SecretAvailability.UNREADABLE;
        }
        try {
            String material = environment.apply(matcher.group(1));
            if (material != null && !material.isBlank()) {
                SecretRedactionPolicy.register(material);
            }
            return material == null || material.isBlank() ? SecretAvailability.MISSING : SecretAvailability.AVAILABLE;
        } catch (RuntimeException failure) {
            return SecretAvailability.UNREADABLE;
        }
    }

    @Override
    public Optional<ProviderCredential> resolveForOutgoingRequest(ExternalSecretReference reference) {
        Matcher matcher = matcher(reference);
        if (matcher == null) {
            return Optional.empty();
        }
        try {
            String material = environment.apply(matcher.group(1));
            if (material != null && !material.isBlank()) {
                SecretRedactionPolicy.register(material);
            }
            return material == null || material.isBlank() ? Optional.empty() : Optional.of(new ProviderCredential(material));
        } catch (RuntimeException failure) {
            return Optional.empty();
        }
    }

    private Matcher matcher(ExternalSecretReference reference) {
        Objects.requireNonNull(reference, "reference");
        Matcher matcher = ENV_REFERENCE.matcher(reference.reference());
        return matcher.matches() ? matcher : null;
    }
}
