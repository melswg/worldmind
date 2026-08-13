package io.github.melswg.worldmind.core.configuration;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Process-local defense-in-depth redaction for credential values in unsafe free-form surfaces. */
public final class SecretRedactionPolicy {
    private static final Set<String> VALUES = ConcurrentHashMap.newKeySet();
    private static final String REDACTED = "[REDACTED]";

    private SecretRedactionPolicy() { }

    public static void register(String value) {
        if (value != null && !value.isBlank()) VALUES.add(value);
    }

    public static String redact(String input) {
        if (input == null) return null;
        String result = input;
        for (String value : VALUES) result = result.replace(value, REDACTED);
        return result;
    }

    public static boolean containsRegisteredSecret(String input) {
        return input != null && VALUES.stream().anyMatch(input::contains);
    }
}
