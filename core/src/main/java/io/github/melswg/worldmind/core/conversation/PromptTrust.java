package io.github.melswg.worldmind.core.conversation;

/** The authority granted to a prompt layer before a provider adapter serializes it. */
public enum PromptTrust {
    TRUSTED_INSTRUCTION,
    UNTRUSTED_DATA
}
