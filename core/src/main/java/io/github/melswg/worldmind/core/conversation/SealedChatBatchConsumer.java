package io.github.melswg.worldmind.core.conversation;

import java.util.concurrent.CompletionStage;

/**
 * The Ticket 07 handoff seam. Completion releases only per-world in-flight
 * ownership; it intentionally has no LLM, HTTP, or player-delivery semantics.
 */
@FunctionalInterface
public interface SealedChatBatchConsumer {
    CompletionStage<?> accept(SealedChatBatch batch);
}
