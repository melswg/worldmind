package io.github.melswg.worldmind.core.conversation;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Deterministic high-recall detector for a configured character name. It is a
 * routing aid only: {@link AddressingSignal#LIKELY} never asserts intent.
 */
public final class CharacterNameAddressingDetector {
    private static final int MAX_FUZZY_NAME_CODE_POINTS = 96;
    private static final int MAX_FUZZY_CANDIDATES = 512;

    private final String normalizedName;
    private final List<String> normalizedNameTokens;
    private final int fuzzyDistance;

    public CharacterNameAddressingDetector(String characterName) {
        normalizedName = normalizeWhole(Objects.requireNonNull(characterName, "characterName"));
        if (normalizedName.isBlank()) {
            throw new IllegalArgumentException("characterName must contain letters or numbers.");
        }
        normalizedNameTokens = List.copyOf(tokens(normalizedName));
        if (normalizedNameTokens.isEmpty()) {
            throw new IllegalArgumentException("characterName must contain letters or numbers.");
        }
        fuzzyDistance = fuzzyDistance(normalizedName.codePointCount(0, normalizedName.length()));
    }

    public AddressingSignal detect(String message) {
        String normalizedMessage = normalizeWhole(Objects.requireNonNull(message, "message"));
        if (normalizedMessage.equals(normalizedName)) {
            return AddressingSignal.EXACT;
        }

        List<String> messageTokens = tokens(normalizedMessage);
        int nameTokenCount = normalizedNameTokens.size();
        int examinedCandidates = 0;
        for (int start = 0; start + nameTokenCount <= messageTokens.size() && examinedCandidates < MAX_FUZZY_CANDIDATES; start++) {
            String candidate = String.join(" ", messageTokens.subList(start, start + nameTokenCount));
            if (candidate.equals(normalizedName)) {
                return AddressingSignal.LIKELY;
            }
            examinedCandidates++;
            int nameLength = normalizedName.codePointCount(0, normalizedName.length());
            int candidateLength = candidate.codePointCount(0, candidate.length());
            boolean conservativeShortNameLength = nameLength <= 6 && candidateLength != nameLength;
            if (fuzzyDistance > 0 && !conservativeShortNameLength && candidateLength <= MAX_FUZZY_NAME_CODE_POINTS
                && boundedEditDistance(normalizedName, candidate, fuzzyDistance) <= fuzzyDistance) {
                return AddressingSignal.LIKELY;
            }
        }
        return AddressingSignal.NONE;
    }

    private static String normalizeWhole(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .toLowerCase(Locale.ROOT)
            .replace('\u0451', '\u0435');
        StringBuilder collapsed = new StringBuilder(normalized.length());
        boolean previousWhitespace = false;
        for (int offset = 0; offset < normalized.length();) {
            int codePoint = normalized.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint) || Character.getType(codePoint) == Character.SPACE_SEPARATOR) {
                if (!previousWhitespace) {
                    collapsed.append(' ');
                    previousWhitespace = true;
                }
            } else {
                collapsed.appendCodePoint(codePoint);
                previousWhitespace = false;
            }
        }
        int first = 0;
        int last = collapsed.length();
        while (first < last) {
            int codePoint = collapsed.codePointAt(first);
            if (!isOuterPunctuation(codePoint) && !Character.isWhitespace(codePoint)) {
                break;
            }
            first += Character.charCount(codePoint);
        }
        while (last > first) {
            int codePoint = collapsed.codePointBefore(last);
            if (!isOuterPunctuation(codePoint) && !Character.isWhitespace(codePoint)) {
                break;
            }
            last -= Character.charCount(codePoint);
        }
        return collapsed.substring(first, last);
    }

    private static boolean isOuterPunctuation(int codePoint) {
        return switch (Character.getType(codePoint)) {
            case Character.CONNECTOR_PUNCTUATION,
                Character.DASH_PUNCTUATION,
                Character.START_PUNCTUATION,
                Character.END_PUNCTUATION,
                Character.INITIAL_QUOTE_PUNCTUATION,
                Character.FINAL_QUOTE_PUNCTUATION,
                Character.OTHER_PUNCTUATION -> true;
            default -> false;
        };
    }

    private static List<String> tokens(String value) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isLetterOrDigit(codePoint)) {
                current.appendCodePoint(codePoint);
            } else if (!current.isEmpty()) {
                tokens.add(current.toString());
                current.setLength(0);
            }
        }
        if (!current.isEmpty()) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    private static int fuzzyDistance(int codePointLength) {
        if (codePointLength <= 3) {
            return 0;
        }
        if (codePointLength <= 6) {
            return 1;
        }
        if (codePointLength <= 14) {
            return 2;
        }
        return 3;
    }

    private static int boundedEditDistance(String expected, String candidate, int maximum) {
        int[] expectedPoints = expected.codePoints().toArray();
        int[] candidatePoints = candidate.codePoints().toArray();
        if (Math.abs(expectedPoints.length - candidatePoints.length) > maximum) {
            return maximum + 1;
        }
        int[] previous = new int[candidatePoints.length + 1];
        int[] current = new int[candidatePoints.length + 1];
        for (int index = 0; index <= candidatePoints.length; index++) {
            previous[index] = index;
        }
        for (int expectedIndex = 1; expectedIndex <= expectedPoints.length; expectedIndex++) {
            current[0] = expectedIndex;
            int rowMinimum = current[0];
            for (int candidateIndex = 1; candidateIndex <= candidatePoints.length; candidateIndex++) {
                int substitution = previous[candidateIndex - 1]
                    + (expectedPoints[expectedIndex - 1] == candidatePoints[candidateIndex - 1] ? 0 : 1);
                current[candidateIndex] = Math.min(
                    Math.min(previous[candidateIndex] + 1, current[candidateIndex - 1] + 1),
                    substitution
                );
                rowMinimum = Math.min(rowMinimum, current[candidateIndex]);
            }
            if (rowMinimum > maximum) {
                return maximum + 1;
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[candidatePoints.length];
    }
}
