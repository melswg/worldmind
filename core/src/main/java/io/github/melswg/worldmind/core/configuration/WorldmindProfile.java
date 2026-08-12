package io.github.melswg.worldmind.core.configuration;

import java.util.List;
import java.util.Objects;

/**
 * Portable character material. It deliberately contains no provider credential
 * and preserves persona, administrator rules, and lore as separate inputs.
 */
public record WorldmindProfile(
    int schemaVersion,
    String characterName,
    String persona,
    String administratorRules,
    List<LoreMaterial> loreMaterials,
    String responseStyle,
    ResponseLengthLimit responseLengthLimit
) {
    public static final int V1_SCHEMA_VERSION = 1;

    public WorldmindProfile {
        if (schemaVersion != V1_SCHEMA_VERSION) {
            throw new IllegalArgumentException("schemaVersion must be exactly " + V1_SCHEMA_VERSION + ".");
        }
        characterName = requireText(characterName, "characterName");
        persona = requireText(persona, "persona");
        administratorRules = requireText(administratorRules, "administratorRules");
        loreMaterials = List.copyOf(Objects.requireNonNull(loreMaterials, "loreMaterials"));
        if (loreMaterials.isEmpty()) {
            throw new IllegalArgumentException("loreMaterials must not be empty.");
        }
        responseStyle = requireText(responseStyle, "responseStyle");
        Objects.requireNonNull(responseLengthLimit, "responseLengthLimit");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }
}
