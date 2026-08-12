package io.github.melswg.worldmind.core.configuration;

import java.util.Objects;
import java.util.Optional;

/**
 * The v1 OpenAI-compatible generation controls. Temperature and top-p are
 * intentionally mutually exclusive so the configuration has one clear policy.
 */
public record GenerationParameters(
    Optional<Double> temperature,
    Optional<Double> topP,
    Optional<Integer> maxOutputTokens
) {
    public GenerationParameters {
        temperature = immutable(temperature, "temperature");
        topP = immutable(topP, "topP");
        maxOutputTokens = immutable(maxOutputTokens, "maxOutputTokens");

        temperature.ifPresent(value -> {
            if (!Double.isFinite(value) || value < 0.0 || value > 2.0) {
                throw new IllegalArgumentException("temperature must be between 0.0 and 2.0.");
            }
        });
        topP.ifPresent(value -> {
            if (!Double.isFinite(value) || value <= 0.0 || value > 1.0) {
                throw new IllegalArgumentException("topP must be greater than 0.0 and at most 1.0.");
            }
        });
        maxOutputTokens.ifPresent(value -> {
            if (value <= 0) {
                throw new IllegalArgumentException("maxOutputTokens must be positive.");
            }
        });
        if (temperature.isPresent() && topP.isPresent()) {
            throw new IllegalArgumentException("temperature and topP cannot both be configured in v1.");
        }
    }

    private static <T> Optional<T> immutable(Optional<T> value, String name) {
        return Objects.requireNonNull(value, name);
    }
}
