package io.github.melswg.worldmind.testkit;

import io.github.melswg.worldmind.core.conversation.LanguageModel;

/** Entry point for the reusable deterministic Worldmind acceptance seam. */
public final class WorldmindTestkit {
    private WorldmindTestkit() {
    }

    public static WorldmindAcceptanceScenario scenario() {
        return new WorldmindAcceptanceScenario();
    }

    public static WorldmindAcceptanceScenario scenario(LanguageModel languageModel) {
        return new WorldmindAcceptanceScenario(languageModel);
    }

    public static FakeSecretResolver secretResolver() {
        return new FakeSecretResolver();
    }
}
