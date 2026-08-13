package io.github.melswg.worldmind.testkit;

/** Standard deterministic behaviours every v0.1 game-context runtime must safely handle. */
public enum GameContextProviderContractCase {
    POSITIVE,
    SLOW,
    THROWING,
    NULL_STAGE,
    NULL_RESULT,
    MALFORMED,
    OVERSIZED,
    HANGING,
    HOSTILE
}
