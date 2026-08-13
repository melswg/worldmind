package io.github.melswg.worldmind.core.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melswg.worldmind.core.configuration.GenerationParameters;
import io.github.melswg.worldmind.core.configuration.ResponseLengthLimit;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PromptSafetyCoreTest {
    @Test
    void selectsPromptDataWithinStableUnicodeBudgetsAndKeepsTheNewestChatSource() {
        String oversized = "🧭".repeat(2_000);
        ProviderRequest selected = PromptBudgetPolicy.select(
            "example-model",
            new GenerationParameters(Optional.empty(), Optional.empty(), Optional.empty()),
            List.of(
                trusted(PromptLayerType.BUILT_IN_SAFETY_POLICY, "built-in", "required safety"),
                trusted(PromptLayerType.ADMINISTRATOR_RULES, "administrator", "A".repeat(9_500)),
                trusted(PromptLayerType.PERSONA, "persona", "calm guide"),
                untrusted(PromptLayerType.LORE, "lore", oversized),
                new PromptLayer(PromptLayerType.MEMORY, PromptTrust.UNTRUSTED_DATA, List.of()),
                untrusted(PromptLayerType.CURRENT_GAME_CONTEXT, "context", oversized),
                new PromptLayer(
                    PromptLayerType.CURRENT_CHAT_BATCH,
                    PromptTrust.UNTRUSTED_DATA,
                    List.of(
                        new PromptFragment("public-chat-message.sequence-1", oversized),
                        new PromptFragment("public-chat-message.sequence-2", oversized)
                    )
                )
            )
        ).orElseThrow();

        assertTrue(PromptBudgetPolicy.estimate(selected) <= PromptBudgetPolicy.MAX_TOTAL_INPUT_CODE_POINTS);
        assertTrue(layer(selected, PromptLayerType.LORE).fragments().isEmpty());
        assertTrue(layer(selected, PromptLayerType.CURRENT_GAME_CONTEXT).fragments().isEmpty());
        PromptFragment newest = layer(selected, PromptLayerType.CURRENT_CHAT_BATCH).fragments().get(0);
        assertEquals("public-chat-message.sequence-2", newest.source());
        assertTrue(newest.content().contains("[worldmind: content truncated]"));
        assertNoUnpairedSurrogates(newest.content());
    }

    @Test
    void refusesToBuildWhenTrustedInstructionsAloneExceedTheLimit() {
        assertTrue(PromptBudgetPolicy.select(
            "example-model",
            new GenerationParameters(Optional.empty(), Optional.empty(), Optional.empty()),
            List.of(
                trusted(PromptLayerType.BUILT_IN_SAFETY_POLICY, "built-in", "required safety"),
                trusted(PromptLayerType.ADMINISTRATOR_RULES, "administrator", "A".repeat(12_000)),
                trusted(PromptLayerType.PERSONA, "persona", "calm guide"),
                new PromptLayer(PromptLayerType.LORE, PromptTrust.UNTRUSTED_DATA, List.of()),
                new PromptLayer(PromptLayerType.MEMORY, PromptTrust.UNTRUSTED_DATA, List.of()),
                new PromptLayer(PromptLayerType.CURRENT_GAME_CONTEXT, PromptTrust.UNTRUSTED_DATA, List.of()),
                untrusted(PromptLayerType.CURRENT_CHAT_BATCH, "public-chat-message.sequence-1", "Aster!")
            )
        ).isEmpty());
    }

    @Test
    void evictsExtensionGameContextBeforeVanillaContextWithoutTouchingTrustedFloor() {
        ProviderRequest selected = PromptBudgetPolicy.select(
            "example-model",
            new GenerationParameters(Optional.empty(), Optional.empty(), Optional.empty()),
            List.of(
                trusted(PromptLayerType.BUILT_IN_SAFETY_POLICY, "built-in", "required safety"),
                trusted(PromptLayerType.ADMINISTRATOR_RULES, "administrator", "A".repeat(9_200)),
                trusted(PromptLayerType.PERSONA, "persona", "calm guide"),
                new PromptLayer(PromptLayerType.LORE, PromptTrust.UNTRUSTED_DATA, List.of()),
                new PromptLayer(PromptLayerType.MEMORY, PromptTrust.UNTRUSTED_DATA, List.of()),
                new PromptLayer(PromptLayerType.CURRENT_GAME_CONTEXT, PromptTrust.UNTRUSTED_DATA, List.of(
                    new PromptFragment("vanilla-game-context", "v".repeat(1_000)),
                    new PromptFragment("extension-game-context:example:season#state", "e".repeat(1_000))
                )),
                untrusted(PromptLayerType.CURRENT_CHAT_BATCH, "public-chat-message.sequence-1", "Aster!")
            )
        ).orElseThrow();

        assertEquals(List.of("vanilla-game-context"), layer(selected, PromptLayerType.CURRENT_GAME_CONTEXT).fragments()
            .stream().map(PromptFragment::source).toList());
        assertEquals(PromptTrust.TRUSTED_INSTRUCTION, layer(selected, PromptLayerType.ADMINISTRATOR_RULES).trust());
    }

    @Test
    void decodesExactlyOnceThenReturnsOnlyAUnicodeSafeLiteralBody() {
        ConversationOutcome nestedDecision = ParticipationProtocol.decode(
            "DIRECT_REPLY\nhello\r\nSILENT\nDIRECT_REPLY",
            new ResponseLengthLimit(80)
        );
        assertEquals(new DirectReply("hello SILENT DIRECT_REPLY"), nestedDecision);

        ConversationOutcome sanitizedEmpty = ParticipationProtocol.decode(
            "AMBIENT_REPLY\n\u00a7a\u202e\u0007",
            new ResponseLengthLimit(80)
        );
        assertEquals(new ConversationRefusal(RefusalCode.EMPTY_RESPONSE), sanitizedEmpty);

        DirectReply emoji = assertInstanceOf(DirectReply.class, ParticipationProtocol.decode(
            "DIRECT_REPLY\n🙂🙂🙂",
            new ResponseLengthLimit(2)
        ));
        assertEquals("🙂🙂", emoji.text());
        assertNoUnpairedSurrogates(emoji.text());
        assertEquals(
            new ConversationRefusal(RefusalCode.INVALID_PROVIDER_RESPONSE),
            ParticipationProtocol.decode("{\"decision\":\"DIRECT_REPLY\"}", new ResponseLengthLimit(80))
        );
    }

    private PromptLayer trusted(PromptLayerType type, String source, String content) {
        return new PromptLayer(type, PromptTrust.TRUSTED_INSTRUCTION, List.of(new PromptFragment(source, content)));
    }

    private PromptLayer untrusted(PromptLayerType type, String source, String content) {
        return new PromptLayer(type, PromptTrust.UNTRUSTED_DATA, List.of(new PromptFragment(source, content)));
    }

    private PromptLayer layer(ProviderRequest request, PromptLayerType type) {
        return request.promptLayers().stream().filter(layer -> layer.type() == type).findFirst().orElseThrow();
    }

    private void assertNoUnpairedSurrogates(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                assertTrue(index + 1 < value.length() && Character.isLowSurrogate(value.charAt(++index)));
            } else {
                assertFalse(Character.isLowSurrogate(current));
            }
        }
    }
}
