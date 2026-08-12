package io.github.melswg.worldmind.fabric;

import io.github.melswg.worldmind.core.configuration.ChatNameColor;
import java.util.Objects;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/** Constructs literal, non-interactive server chat components for Worldmind. */
final class FabricWorldmindChatRenderer {
    private FabricWorldmindChatRenderer() {
    }

    static Text reply(String characterName, ChatNameColor color, String text) {
        return Text.empty()
            .append(Text.literal("<" + requireText(characterName, "characterName") + "> ")
                .formatted(formatting(Objects.requireNonNull(color, "color"))))
            .append(Text.literal(requireText(text, "text")));
    }

    static Text unavailable(String characterName, ChatNameColor color) {
        return reply(characterName, color, "I can't answer right now.");
    }

    private static Formatting formatting(ChatNameColor color) {
        return switch (color) {
            case BLACK -> Formatting.BLACK;
            case DARK_BLUE -> Formatting.DARK_BLUE;
            case DARK_GREEN -> Formatting.DARK_GREEN;
            case DARK_AQUA -> Formatting.DARK_AQUA;
            case DARK_RED -> Formatting.DARK_RED;
            case DARK_PURPLE -> Formatting.DARK_PURPLE;
            case GOLD -> Formatting.GOLD;
            case GRAY -> Formatting.GRAY;
            case DARK_GRAY -> Formatting.DARK_GRAY;
            case BLUE -> Formatting.BLUE;
            case GREEN -> Formatting.GREEN;
            case AQUA -> Formatting.AQUA;
            case RED -> Formatting.RED;
            case LIGHT_PURPLE -> Formatting.LIGHT_PURPLE;
            case YELLOW -> Formatting.YELLOW;
            case WHITE -> Formatting.WHITE;
        };
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }
}
