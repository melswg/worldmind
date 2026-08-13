package io.github.melswg.worldmind.core.conversation;

import io.github.melswg.worldmind.core.configuration.LoreMaterial;
import io.github.melswg.worldmind.core.configuration.WorldmindProfile;
import io.github.melswg.worldmind.core.memory.MemoryFact;
import io.github.melswg.worldmind.core.memory.MemoryRecord;
import io.github.melswg.worldmind.core.memory.RelationshipMemory;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Builds the fixed v1 prompt structure independently of provider transport details. */
final class ConversationPromptBuilder {
    Optional<ProviderRequest> build(NormalizedServerRequest request, List<MemoryRecord> recalledMemory) {
        Objects.requireNonNull(request, "request");
        recalledMemory = List.copyOf(Objects.requireNonNull(recalledMemory, "recalledMemory"));
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
            recalledMemory.stream().map(this::memoryFragment).toList()
        ));
        layers.add(new PromptLayer(
            PromptLayerType.CURRENT_GAME_CONTEXT,
            PromptTrust.UNTRUSTED_DATA,
            request.chatBatch().currentContextSnapshot().stream().map(this::contextFragment).toList()
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

    private PromptFragment memoryFragment(MemoryRecord record) {
        String header = "recordType: " + (record instanceof MemoryFact ? "FACT" : "RELATIONSHIP") + "\n"
            + "state: " + record.state() + "\n"
            + "scope: " + serializeScope(record) + "\n"
            + "visibility: " + record.visibility() + "\n"
            + "sourceBatchId: " + record.provenance().sourceBatchId() + "\n"
            + "sourceSequenceRange: " + record.provenance().sourceRange().firstSequence()
                + "-" + record.provenance().sourceRange().lastSequence() + "\n"
            + "sourceTimestamp: " + record.sourceTimestamp() + "\n"
            + "recordedAt: " + record.recordedAt() + "\n"
            + "confidence: " + record.confidence().value() + "\n"
            + "importance: " + record.importance().value() + "\n"
            + "confirmation: " + record.confirmation().map(value -> value.authority() + ":" + value.authorityIdentifier()
                + " at " + value.confirmedAt()).orElse("none") + "\n";
        if (record instanceof MemoryFact fact) {
            return new PromptFragment("world-memory-record." + fact.id().value(), header + "content: " + fact.content());
        }
        RelationshipMemory relationship = (RelationshipMemory) record;
        return new PromptFragment(
            "world-memory-record." + relationship.id().value(),
            header + "relationshipSubjectUuid: " + relationship.subjectPlayerId() + "\n"
                + "relationshipState: " + relationship.relationshipState()
        );
    }

    private String serializeScope(MemoryRecord record) {
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
