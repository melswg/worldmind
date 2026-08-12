package io.github.melswg.worldmind.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.melswg.worldmind.core.configuration.ChatNameColor;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.Formatting;
import org.junit.jupiter.api.Test;

class FabricWorldmindChatRendererTest {
    @Test
    void rendersAColoredLiteralPrefixAndAnUnparsedLiteralBody() {
        Text rendered = FabricWorldmindChatRenderer.reply(
            "Майни",
            ChatNameColor.LIGHT_PURPLE,
            "/op @a [link](https://example.invalid) {\"clickEvent\":true}"
        );

        assertEquals("<Майни> /op @a [link](https://example.invalid) {\"clickEvent\":true}", rendered.getString());
        assertNull(rendered.getStyle().getColor());
        assertEquals(2, rendered.getSiblings().size());
        assertEquals(TextColor.fromFormatting(Formatting.LIGHT_PURPLE), rendered.getSiblings().get(0).getStyle().getColor());
        assertNull(rendered.getSiblings().get(1).getStyle().getColor());
        assertNoInteractiveStyle(rendered);
        assertNoInteractiveStyle(rendered.getSiblings().get(0));
        assertNoInteractiveStyle(rendered.getSiblings().get(1));
    }

    @Test
    void keepsHostileFormattingAndInteractionLookingTextLiteralAndUnstyled() {
        Text rendered = FabricWorldmindChatRenderer.reply(
            "Aster",
            ChatNameColor.GOLD,
            "\u00a7a /function run\n{\"clickEvent\":{\"action\":\"run_command\"}} https://example.invalid"
        );

        assertEquals(
            "<Aster> \u00a7a /function run\n{\"clickEvent\":{\"action\":\"run_command\"}} https://example.invalid",
            rendered.getString()
        );
        assertNoInteractiveStyle(rendered);
        assertNoInteractiveStyle(rendered.getSiblings().get(0));
        assertNoInteractiveStyle(rendered.getSiblings().get(1));
        assertNull(rendered.getSiblings().get(1).getStyle().getColor());
    }

    @Test
    void mapsEveryPortablePaletteValueAndUsesLightPurpleForTheOldProfileConstructor() {
        for (ChatNameColor color : ChatNameColor.values()) {
            Text rendered = FabricWorldmindChatRenderer.reply("Aster", color, "hello");
            assertEquals(
                TextColor.fromFormatting(Formatting.valueOf(color.name())),
                rendered.getSiblings().get(0).getStyle().getColor()
            );
        }
        assertEquals(
            TextColor.fromFormatting(Formatting.LIGHT_PURPLE),
            FabricWorldmindChatRenderer.unavailable("Aster", ChatNameColor.LIGHT_PURPLE).getSiblings().get(0).getStyle().getColor()
        );
    }

    private void assertNoInteractiveStyle(Text text) {
        assertNull(text.getStyle().getClickEvent());
        assertNull(text.getStyle().getHoverEvent());
        assertNull(text.getStyle().getInsertion());
    }
}
