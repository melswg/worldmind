package io.github.melswg.worldmind.core.conversation;

import java.util.Objects;

/**
 * Stable provider-neutral early input-size estimate. It counts Unicode code
 * points in source-attributed player and vanilla-context data plus fixed wire
 * labels. It deliberately does not claim to be a provider token count.
 */
public final class ChatBatchInputEstimator {
    private static final long PLAYER_MESSAGE_OVERHEAD = 32;
    private static final long CONTEXT_OVERHEAD = 16;

    private ChatBatchInputEstimator() {
    }

    public static long estimate(ObservedPublicChatMessage message) {
        Objects.requireNonNull(message, "message");
        long total = PLAYER_MESSAGE_OVERHEAD;
        total = saturatedAdd(total, codePointCount(message.requester().playerName()));
        total = saturatedAdd(total, codePointCount(message.message()));
        for (UntrustedContext context : message.currentContext()) {
            total = saturatedAdd(total, CONTEXT_OVERHEAD);
            total = saturatedAdd(total, codePointCount(context.source()));
            total = saturatedAdd(total, codePointCount(context.content()));
        }
        return total;
    }

    public static long saturatedAdd(long left, long right) {
        if (Long.MAX_VALUE - left < right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static long codePointCount(String value) {
        return value.codePointCount(0, value.length());
    }
}
