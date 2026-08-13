package io.github.melswg.worldmind.core.administration;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

/** Opaque, scope-bound keyset position. It never carries message text or player display names. */
public record MemoryInspectionCursor(
    MemoryRecordType recordType,
    String scopeFingerprint,
    long lastSequence,
    long firstSequence,
    long recordedAtEpochMillis,
    String stableIdentity
) {
    private static final String VERSION = "v1";

    public MemoryInspectionCursor {
        Objects.requireNonNull(recordType, "recordType");
        scopeFingerprint = requireToken(scopeFingerprint, "scopeFingerprint");
        stableIdentity = requireToken(stableIdentity, "stableIdentity");
    }

    public String encode() {
        String raw = String.join("|", VERSION, recordType.name(), scopeFingerprint, Long.toString(lastSequence),
            Long.toString(firstSequence), Long.toString(recordedAtEpochMillis), stableIdentity);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static MemoryInspectionCursor decode(String encoded, MemoryRecordType expectedType, MemoryInspectionScope expectedScope) {
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.length() > 256) throw new IllegalArgumentException("Invalid cursor.");
        try {
            String[] fields = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8).split("\\|", -1);
            if (fields.length != 7 || !VERSION.equals(fields[0])) throw new IllegalArgumentException("Invalid cursor.");
            MemoryRecordType type = MemoryRecordType.valueOf(fields[1]);
            if (type != expectedType || !fields[2].equals(expectedScope.fingerprint())) throw new IllegalArgumentException("Invalid cursor.");
            return new MemoryInspectionCursor(type, fields[2], Long.parseLong(fields[3]), Long.parseLong(fields[4]),
                Long.parseLong(fields[5]), fields[6]);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid cursor.");
        }
    }

    private static String requireToken(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.indexOf('|') >= 0 || value.length() > 128) throw new IllegalArgumentException("Invalid cursor.");
        return value;
    }
}
