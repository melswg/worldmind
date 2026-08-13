package io.github.melswg.worldmind.api.gamecontext.v1;

import java.util.Locale;
import java.util.Objects;

/** Canonical, stable identity of an external game-context provider. */
public record GameContextSource(String namespace, String path) implements Comparable<GameContextSource> {
    public GameContextSource {
        namespace = requireCanonical(namespace, "namespace", "[a-z0-9_.-]+");
        path = requireCanonical(path, "path", "[a-z0-9/._-]+");
        if (path.startsWith("/") || path.endsWith("/")
            || java.util.Arrays.stream(path.split("/", -1)).anyMatch(segment -> segment.equals(".") || segment.equals(".."))) {
            throw new IllegalArgumentException("path must not contain relative path segments.");
        }
    }

    @Override
    public int compareTo(GameContextSource other) {
        return canonicalName().compareTo(Objects.requireNonNull(other, "other").canonicalName());
    }

    public String canonicalName() {
        return namespace + ":" + path;
    }

    @Override
    public String toString() {
        return canonicalName();
    }

    private static String requireCanonical(String value, String name, String pattern) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || !value.equals(value.toLowerCase(Locale.ROOT)) || !value.matches(pattern)) {
            throw new IllegalArgumentException(name + " must be a non-empty canonical lowercase identifier.");
        }
        return value;
    }
}
