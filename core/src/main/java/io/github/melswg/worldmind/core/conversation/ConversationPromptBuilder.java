package io.github.melswg.worldmind.core.conversation;

import io.github.melswg.worldmind.core.configuration.LoreMaterial;
import io.github.melswg.worldmind.core.configuration.WorldmindProfile;
import io.github.melswg.worldmind.core.memory.RetrievedMemoryContext;
import io.github.melswg.worldmind.core.memory.RetrievedMemoryEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Builds the fixed v1 prompt structure independently of provider transport details. */
final class ConversationPromptBuilder {
    Optional<ProviderRequest> build(
        NormalizedServerRequest request,
        RetrievedMemoryContext recalledMemory,
        List<UntrustedContext> currentGameContext
    ) {
        Objects.requireNonNull(request, "request");
        recalledMemory = Objects.requireNonNull(recalledMemory, "recalledMemory");
        currentGameContext = List.copyOf(Objects.requireNonNull(currentGameContext, "currentGameContext"));
        WorldmindProfile profile = request.validatedConfiguration().profile();
        List<PromptLayer> layers = new ArrayList<>();
        layers.add(new PromptLayer(
            PromptLayerType.BUILT_IN_SAFETY_POLICY,
            PromptTrust.TRUSTED_INSTRUCTION,
            List.of(
                new PromptFragment("worldmind.built-in-safety-policy", BuiltInSafetyPolicy.CONTENT),
                new PromptFragment(ParticipationProtocol.SOURCE, ParticipationProtocol.CONTENT)
            )
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
        layers.add(new PromptLayer(
            PromptLayerType.MEMORY,
            PromptTrust.UNTRUSTED_DATA,
            recalledMemory.entriesInPromptOrder().stream().map(this::memoryFragment).toList()
        ));
        layers.add(new PromptLayer(
            PromptLayerType.CURRENT_GAME_CONTEXT,
            PromptTrust.UNTRUSTED_DATA,
            currentGameContext.stream().map(this::contextFragment).toList()
        ));
        layers.add(new PromptLayer(
            PromptLayerType.CURRENT_CHAT_BATCH,
            PromptTrust.UNTRUSTED_DATA,
            request.chatBatch().messages().stream().map(this::chatMessageFragment).toList()
        ));

        return PromptBudgetPolicy.select(
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

    private PromptFragment memoryFragment(RetrievedMemoryEntry record) {
        String header = "recordType: " + record.type() + "\n"
            + "scope: " + serializeScope(record) + "\n"
            + "visibility: " + record.visibility() + "\n"
            + "sourceBatchIds: " + record.provenance().sourceBatchIds() + "\n"
            + "sourceSequenceRange: " + record.provenance().sourceRange().firstSequence()
                + "-" + record.provenance().sourceRange().lastSequence() + "\n"
            + "sourceTimestamp: " + record.sourceTimestamp() + "\n"
            + "recordedAt: " + record.recordedAt() + "\n"
            + "confidence: " + record.confidence().value() + "\n"
            + "importance: " + record.importance().value() + "\n";
        return new PromptFragment("world-memory-record." + record.identity(), header + "content: " + record.content());
    }

    private String serializeScope(RetrievedMemoryEntry record) {
        if (record.scope() instanceof io.github.melswg.worldmind.core.memory.MemoryScope.World) {
            return "WORLD";
        }
        return "PLAYER:" + ((io.github.melswg.worldmind.core.memory.MemoryScope.Player) record.scope()).playerId();
    }

    private PromptFragment chatMessageFragment(ObservedPublicChatMessage message) {
        return new PromptFragment(
            "public-chat-message.sequence-" + message.sequence(),
            "visiblePlayerName: " + message.requester().playerName() + "\n"
                + "addressingSignal: " + message.addressingSignal() + "\n"
                + "message: " + message.message()
        );
    }

    private String serializePersona(WorldmindProfile profile) {
        return "characterName: " + profile.characterName() + "\n"
            + "persona: " + profile.persona() + "\n"
            + "responseStyle: " + profile.responseStyle() + "\n"
            + "responseLengthLimit: " + profile.responseLengthLimit().maxCharacters() + " characters";
    }
}
