package io.github.melswg.worldmind.core.conversation;

import io.github.melswg.worldmind.core.configuration.LoreMaterial;
import io.github.melswg.worldmind.core.configuration.WorldmindProfile;
import java.util.ArrayList;
import java.util.List;

/** Builds the fixed v1 prompt structure independently of provider transport details. */
final class ConversationPromptBuilder {
    ProviderRequest build(NormalizedServerRequest request) {
        WorldmindProfile profile = request.validatedConfiguration().profile();
        List<PromptLayer> layers = new ArrayList<>();
        layers.add(trusted(
            PromptLayerType.BUILT_IN_SAFETY_POLICY,
            "worldmind.built-in-safety-policy",
            BuiltInSafetyPolicy.CONTENT
        ));
        layers.add(trusted(
            PromptLayerType.ADMINISTRATOR_RULES,
            "profile.administrator-rules",
            profile.administratorRules()
        ));
        layers.add(trusted(
            PromptLayerType.PERSONA,
            "profile.persona",
            serializePersona(profile)
        ));
        layers.add(new PromptLayer(
            PromptLayerType.LORE,
            PromptTrust.UNTRUSTED_DATA,
            profile.loreMaterials().stream().map(this::loreFragment).toList()
        ));
        layers.add(new PromptLayer(PromptLayerType.MEMORY, PromptTrust.UNTRUSTED_DATA, List.of()));
        layers.add(new PromptLayer(
            PromptLayerType.CURRENT_GAME_CONTEXT,
            PromptTrust.UNTRUSTED_DATA,
            request.currentGameContext().stream().map(this::contextFragment).toList()
        ));
        layers.add(new PromptLayer(
            PromptLayerType.PLAYER_MESSAGE,
            PromptTrust.UNTRUSTED_DATA,
            List.of(new PromptFragment("player-message", request.message()))
        ));

        return new ProviderRequest(
            request.validatedConfiguration().globalConfiguration().provider().model(),
            request.validatedConfiguration().globalConfiguration().provider().generationParameters(),
            layers
        );
    }

    private PromptLayer trusted(PromptLayerType type, String source, String content) {
        return new PromptLayer(type, PromptTrust.TRUSTED_INSTRUCTION, List.of(new PromptFragment(source, content)));
    }

    private PromptFragment loreFragment(LoreMaterial material) {
        return new PromptFragment(material.name(), material.content());
    }

    private PromptFragment contextFragment(UntrustedContext context) {
        return new PromptFragment(context.source(), context.content());
    }

    private String serializePersona(WorldmindProfile profile) {
        return "characterName: " + profile.characterName() + "\n"
            + "persona: " + profile.persona() + "\n"
            + "responseStyle: " + profile.responseStyle() + "\n"
            + "responseLengthLimit: " + profile.responseLengthLimit().maxCharacters() + " characters";
    }
}
