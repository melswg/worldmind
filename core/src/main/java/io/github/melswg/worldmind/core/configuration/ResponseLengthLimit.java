package io.github.melswg.worldmind.core.configuration;

/** A profile-owned maximum length for an eventual Minecraft chat response. */
public record ResponseLengthLimit(int maxCharacters) {
    public ResponseLengthLimit {
        if (maxCharacters <= 0) {
            throw new IllegalArgumentException("maxCharacters must be positive.");
        }
    }
}
