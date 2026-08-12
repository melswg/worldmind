package io.github.melswg.worldmind.core.conversation;

import io.github.melswg.worldmind.core.configuration.ResponseLengthLimit;
import java.util.Objects;

/** Normalizes model reply text into literal, non-formatting Minecraft-safe text. */
final class ReplyBodySanitizer {
    private ReplyBodySanitizer() {
    }

    static String sanitize(String rawBody, ResponseLengthLimit responseLengthLimit) {
        Objects.requireNonNull(rawBody, "rawBody");
        Objects.requireNonNull(responseLengthLimit, "responseLengthLimit");
        String normalizedLineEndings = rawBody.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder sanitized = new StringBuilder(normalizedLineEndings.length());
        boolean previousWasWhitespace = true;
        for (int index = 0; index < normalizedLineEndings.length();) {
            int codePoint = normalizedLineEndings.codePointAt(index);
            index += Character.charCount(codePoint);
            if (codePoint == '\u00A7') {
                index = skipLegacyFormattingCode(normalizedLineEndings, index);
                continue;
            }
            if (isUnpairedSurrogate(codePoint)) {
                appendSpace(sanitized, previousWasWhitespace);
                previousWasWhitespace = true;
                continue;
            }
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                appendSpace(sanitized, previousWasWhitespace);
                previousWasWhitespace = true;
                continue;
            }
            if (isUnsafeControlOrFormatting(codePoint)) {
                continue;
            }
            sanitized.appendCodePoint(codePoint);
            previousWasWhitespace = false;
        }
        trimTrailingSpace(sanitized);
        return prefixByCodePoints(sanitized, responseLengthLimit.maxCharacters());
    }

    private static boolean isUnpairedSurrogate(int codePoint) {
        return codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE;
    }

    private static boolean isUnsafeControlOrFormatting(int codePoint) {
        return Character.getType(codePoint) == Character.CONTROL || isUnsafeFormatControl(codePoint);
    }

    private static boolean isUnsafeFormatControl(int codePoint) {
        return codePoint == 0x00AD
            || codePoint == 0x061C
            || codePoint == 0x180E
            || (codePoint >= 0x200B && codePoint <= 0x200C)
            || (codePoint >= 0x200E && codePoint <= 0x200F)
            || (codePoint >= 0x202A && codePoint <= 0x202E)
            || (codePoint >= 0x2060 && codePoint <= 0x2069)
            || codePoint == 0xFEFF;
    }

    private static int skipLegacyFormattingCode(String value, int index) {
        if (index >= value.length()) {
            return index;
        }
        int candidate = value.codePointAt(index);
        return isLegacyFormattingCode(candidate) ? index + Character.charCount(candidate) : index;
    }

    private static boolean isLegacyFormattingCode(int codePoint) {
        return (codePoint >= '0' && codePoint <= '9')
            || (codePoint >= 'a' && codePoint <= 'f')
            || (codePoint >= 'k' && codePoint <= 'o')
            || codePoint == 'r'
            || (codePoint >= 'A' && codePoint <= 'F')
            || (codePoint >= 'K' && codePoint <= 'O')
            || codePoint == 'R'
            || codePoint == 'x'
            || codePoint == 'X';
    }

    private static void appendSpace(StringBuilder value, boolean previousWasWhitespace) {
        if (!previousWasWhitespace && value.length() > 0) {
            value.append(' ');
        }
    }

    private static void trimTrailingSpace(StringBuilder value) {
        if (!value.isEmpty() && value.charAt(value.length() - 1) == ' ') {
            value.setLength(value.length() - 1);
        }
    }

    private static String prefixByCodePoints(StringBuilder value, int maximumCodePoints) {
        int end = 0;
        int counted = 0;
        while (end < value.length() && counted < maximumCodePoints) {
            int codePoint = value.codePointAt(end);
            end += Character.charCount(codePoint);
            counted++;
        }
        return value.substring(0, end);
    }
}
