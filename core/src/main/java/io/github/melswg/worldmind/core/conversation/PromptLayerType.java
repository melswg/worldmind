package io.github.melswg.worldmind.core.conversation;

/** The fixed semantic positions in a provider-neutral Worldmind conversation. */
public enum PromptLayerType {
    BUILT_IN_SAFETY_POLICY,
    ADMINISTRATOR_RULES,
    PERSONA,
    LORE,
    MEMORY,
    CURRENT_GAME_CONTEXT,
    PLAYER_MESSAGE
}
