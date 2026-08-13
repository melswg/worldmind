package io.github.melswg.worldmind.gamecontext.internal;

import io.github.melswg.worldmind.api.gamecontext.v1.GameContextEntry;
import io.github.melswg.worldmind.api.gamecontext.v1.GameContextLimits;
import io.github.melswg.worldmind.api.gamecontext.v1.GameContextResult;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Normalizes external values before they can become source-attributed untrusted prompt fragments. */
public final class GameContextNormalizer {
    private GameContextNormalizer() {
    }

    public static List<GameContextEntry> normalize(GameContextResult result) throws ValidationFailure {
        if (result == null) throw new ValidationFailure(GameContextDiagnosticCode.NULL_RESULT);
        List<GameContextEntry> entries;
        try {
            entries = result.entries();
        } catch (RuntimeException failure) {
            throw new ValidationFailure(GameContextDiagnosticCode.MALFORMED_RESULT);
        }
        if (entries == null || entries.size() > GameContextLimits.MAX_ENTRIES_PER_RESULT) {
            throw new ValidationFailure(entries != null && entries.size() > GameContextLimits.MAX_ENTRIES_PER_RESULT
                ? GameContextDiagnosticCode.OVERSIZED_RESULT : GameContextDiagnosticCode.MALFORMED_RESULT);
        }
        List<GameContextEntry> normalized = new ArrayList<>(entries.size());
        Set<String> keys = new HashSet<>();
        long resultSize = 0;
        for (GameContextEntry entry : entries) {
            if (entry == null) throw new ValidationFailure(GameContextDiagnosticCode.MALFORMED_RESULT);
            String key;
            String value;
            try {
                key = clean(entry.key());
                value = clean(entry.value());
            } catch (RuntimeException failure) {
                throw new ValidationFailure(GameContextDiagnosticCode.MALFORMED_RESULT);
            }
            if (key.isBlank() || value.isBlank() || !key.matches("[a-z0-9][a-z0-9._-]*")) {
                throw new ValidationFailure(GameContextDiagnosticCode.MALFORMED_RESULT);
            }
            int keySize = key.codePointCount(0, key.length());
            int valueSize = value.codePointCount(0, value.length());
            if (keySize > GameContextLimits.MAX_ENTRY_KEY_CODE_POINTS || valueSize > GameContextLimits.MAX_ENTRY_VALUE_CODE_POINTS) {
                throw new ValidationFailure(GameContextDiagnosticCode.OVERSIZED_RESULT);
            }
            resultSize += (long) keySize + valueSize;
            if (resultSize > GameContextLimits.MAX_RESULT_CODE_POINTS) {
                throw new ValidationFailure(GameContextDiagnosticCode.OVERSIZED_RESULT);
            }
            if (!keys.add(key)) throw new ValidationFailure(GameContextDiagnosticCode.MALFORMED_RESULT);
            normalized.add(new GameContextEntry(key, value));
        }
        return normalized.stream().sorted(java.util.Comparator.comparing(GameContextEntry::key)).toList();
    }

    private static String clean(String raw) {
        if (raw == null) throw new IllegalArgumentException("External context must not be null.");
        String normalized = Normalizer.normalize(raw.replace("\r\n", "\n").replace('\r', '\n'), Normalizer.Form.NFC);
        StringBuilder clean = new StringBuilder(normalized.length());
        for (int offset = 0; offset < normalized.length();) {
            int point = normalized.codePointAt(offset);
            offset += Character.charCount(point);
            if (isRejected(point)) continue;
            clean.appendCodePoint(point);
        }
        return clean.toString();
    }

    private static boolean isRejected(int point) {
        return point == 0x7F
            || (point >= 0xD800 && point <= 0xDFFF)
            || (Character.isISOControl(point) && point != '\n')
            || (point >= 0x202A && point <= 0x202E)
            || (point >= 0x2066 && point <= 0x2069)
            || point == 0x200E || point == 0x200F;
    }

    /** Internal signal that carries only a safe category, never an external string. */
    public static final class ValidationFailure extends Exception {
        private final GameContextDiagnosticCode code;

        private ValidationFailure(GameContextDiagnosticCode code) {
            this.code = code;
        }

        public GameContextDiagnosticCode code() {
            return code;
        }
    }
}
