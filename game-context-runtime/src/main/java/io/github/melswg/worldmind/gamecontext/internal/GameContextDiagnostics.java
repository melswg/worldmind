package io.github.melswg.worldmind.gamecontext.internal;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** Keeps only the last safe diagnostic; returned provider data and exceptions are intentionally discarded. */
public final class GameContextDiagnostics {
    private final AtomicReference<GameContextDiagnostic> latest = new AtomicReference<>();

    public void record(GameContextDiagnostic diagnostic) {
        latest.set(Objects.requireNonNull(diagnostic, "diagnostic"));
    }

    public Optional<GameContextDiagnostic> latest() {
        return Optional.ofNullable(latest.get());
    }
}
