package io.github.melswg.worldmind.api.gamecontext.v1;

/** Fixed v0.1 safety limits for third-party game-context integrations. */
public final class GameContextLimits {
    public static final int MAX_PROVIDERS = 32;
    public static final long CALLBACK_TIMEOUT_MILLIS = 500;
    public static final int MAX_ENTRIES_PER_RESULT = 8;
    public static final int MAX_ENTRY_KEY_CODE_POINTS = 64;
    public static final int MAX_ENTRY_VALUE_CODE_POINTS = 512;
    public static final int MAX_RESULT_CODE_POINTS = 1_024;

    private GameContextLimits() {
    }
}
