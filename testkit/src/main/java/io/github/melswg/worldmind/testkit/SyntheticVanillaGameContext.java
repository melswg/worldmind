package io.github.melswg.worldmind.testkit;

import io.github.melswg.worldmind.core.conversation.UntrustedContext;
import java.util.Objects;

/**
 * A Minecraft-client-free representation of the vanilla facts an adapter can
 * normalize before crossing into core.
 */
public record SyntheticVanillaGameContext(
    String worldName,
    String dimension,
    long gameTime,
    String weather
) {
    public SyntheticVanillaGameContext {
        worldName = requireText(worldName, "worldName");
        dimension = requireText(dimension, "dimension");
        weather = requireText(weather, "weather");
        if (gameTime < 0) {
            throw new IllegalArgumentException("gameTime must not be negative.");
        }
    }

    public static SyntheticVanillaGameContext overworld(String worldName) {
        return new SyntheticVanillaGameContext(worldName, "minecraft:overworld", 0, "clear");
    }

    public SyntheticVanillaGameContext atGameTime(long newGameTime) {
        return new SyntheticVanillaGameContext(worldName, dimension, newGameTime, weather);
    }

    public SyntheticVanillaGameContext withWeather(String newWeather) {
        return new SyntheticVanillaGameContext(worldName, dimension, gameTime, newWeather);
    }

    public UntrustedContext asUntrustedContext() {
        return new UntrustedContext(
            "vanilla-game-context",
            "world=" + worldName + "; dimension=" + dimension + "; gameTime=" + gameTime + "; weather=" + weather
        );
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }
}
