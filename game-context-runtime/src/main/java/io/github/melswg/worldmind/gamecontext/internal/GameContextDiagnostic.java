package io.github.melswg.worldmind.gamecontext.internal;

import io.github.melswg.worldmind.api.gamecontext.v1.GameContextSource;
import java.util.Objects;

/** A redaction-safe provider identity and failure category, never returned context or Throwable detail. */
public record GameContextDiagnostic(GameContextSource source, GameContextDiagnosticCode code) {
    public GameContextDiagnostic {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(code, "code");
    }
}
