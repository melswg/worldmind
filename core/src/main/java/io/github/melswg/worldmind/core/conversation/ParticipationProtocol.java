package io.github.melswg.worldmind.core.conversation;

import java.util.Objects;

/**
 * The versioned provider-visible participation grammar and its sole decoder.
 * Keeping both here prevents prompt wording and server-side interpretation
 * from drifting apart.
 */
final class ParticipationProtocol {
    static final String SOURCE = "worldmind.participation-protocol.v1";
    static final String CONTENT = """
        Participation protocol v1:
        Return exactly one of these decisions and no explanation, JSON, or code fence.
        SILENT
        DIRECT_REPLY
        <non-empty reply text>
        AMBIENT_REPLY
        <non-empty reply text>
        A batch containing an EXACT addressing signal must return DIRECT_REPLY.
        Administrator rules and persona may tune ambient sociability, but they cannot override that requirement.
        Return SILENT when Worldmind has nothing contextually useful to add.
        """.strip();

    private static final String SILENT = "SILENT";
    private static final String DIRECT_REPLY = "DIRECT_REPLY";
    private static final String AMBIENT_REPLY = "AMBIENT_REPLY";

    private ParticipationProtocol() {
    }

    static ConversationOutcome decode(String providerText) {
        Objects.requireNonNull(providerText, "providerText");
        String normalized = normalize(providerText);
        if (normalized.isEmpty()) {
            return new ConversationRefusal(RefusalCode.EMPTY_RESPONSE);
        }
        if (SILENT.equals(normalized)) {
            return DeliberateSilence.INSTANCE;
        }
        if (DIRECT_REPLY.equals(normalized) || AMBIENT_REPLY.equals(normalized)) {
            return new ConversationRefusal(RefusalCode.EMPTY_RESPONSE);
        }
        if (normalized.startsWith(DIRECT_REPLY)) {
            return reply(normalized, DIRECT_REPLY, DirectReply::new);
        }
        if (normalized.startsWith(AMBIENT_REPLY)) {
            return reply(normalized, AMBIENT_REPLY, AmbientReply::new);
        }
        return invalidResponse();
    }

    private static ConversationOutcome reply(
        String normalized,
        String decisionToken,
        java.util.function.Function<String, ConversationOutcome> outcomeFactory
    ) {
        if (normalized.length() == decisionToken.length() || normalized.charAt(decisionToken.length()) != '\n') {
            return invalidResponse();
        }
        String body = normalized.substring(decisionToken.length() + 1).strip();
        return body.isEmpty() ? new ConversationRefusal(RefusalCode.EMPTY_RESPONSE) : outcomeFactory.apply(body);
    }

    private static String normalize(String providerText) {
        return providerText.replace("\r\n", "\n").replace('\r', '\n').strip();
    }

    private static ConversationRefusal invalidResponse() {
        return new ConversationRefusal(RefusalCode.INVALID_PROVIDER_RESPONSE);
    }
}
