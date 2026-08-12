package io.github.melswg.worldmind.testkit;

/** Entry point for the reusable deterministic Worldmind acceptance seam. */
public final class WorldmindTestkit {
    private WorldmindTestkit() {
    }

    public static WorldmindAcceptanceScenario scenario() {
        return new WorldmindAcceptanceScenario();
    }
}
