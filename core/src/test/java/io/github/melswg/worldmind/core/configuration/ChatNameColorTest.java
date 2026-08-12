package io.github.melswg.worldmind.core.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import org.junit.jupiter.api.Test;

class ChatNameColorTest {
    @Test
    void exposesTheExactPortableVanillaPaletteWithoutCaseFolding() {
        assertEquals(
            List.of(
                "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray",
                "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white"
            ),
            java.util.Arrays.stream(ChatNameColor.values()).map(ChatNameColor::profileValue).toList()
        );
        assertEquals(ChatNameColor.LIGHT_PURPLE, ChatNameColor.fromProfileValue("light_purple").orElseThrow());
        assertFalse(ChatNameColor.fromProfileValue("LIGHT_PURPLE").isPresent());
    }
}
