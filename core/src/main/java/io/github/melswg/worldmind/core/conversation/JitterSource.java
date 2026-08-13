package io.github.melswg.worldmind.core.conversation;

/** Injectable source of bounded retry variation; tests supply deterministic values. */
@FunctionalInterface
public interface JitterSource {
    /** Returns a value in the inclusive range -1.0 through 1.0. */
    double nextUnitJitter();

    static JitterSource random() {
        return () -> java.util.concurrent.ThreadLocalRandom.current().nextDouble(-1.0, 1.0);
    }
}
