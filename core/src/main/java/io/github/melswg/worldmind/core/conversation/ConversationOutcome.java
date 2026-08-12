package io.github.melswg.worldmind.core.conversation;

/** A result that can safely cross the core-to-server boundary. */
public sealed interface ConversationOutcome permits AmbientReply, ConversationRefusal, DeliberateSilence, DirectReply {
}
