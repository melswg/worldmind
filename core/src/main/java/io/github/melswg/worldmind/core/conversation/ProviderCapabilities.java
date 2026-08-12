package io.github.melswg.worldmind.core.conversation;

/** The minimum provider behavior needed to preserve the v1 trusted prompt layers. */
public record ProviderCapabilities(boolean supportsSystemInstructions) {
}
