package io.github.melswg.worldmind.core.memory;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Local fallback generator for v1. It is deliberately a separate boundary so
 * an operator can replace it with a provider-backed generator later without
 * coupling it to conversation participation.
 */
public final class DeterministicMemoryCompactionGenerator implements MemoryCompactionGenerator {
    @Override
    public CompletionStage<MemoryCompactionResult> compact(MemoryCompactionInput input) {
        String dialogue = input.sources().stream()
            .map(source -> source.requester().playerName() + ": " + source.text())
            .reduce((left, right) -> left + "\n" + right).orElseThrow();
        String bounded = truncate(dialogue, 1_200);
        DerivedMemoryCandidate summary = new DerivedMemoryCandidate(
            MemoryScope.world(), MemoryVisibility.PUBLIC, new MemoryConfidence(0.5), new MemoryImportance(0.5), bounded
        );
        DerivedMemoryCandidate situation = new DerivedMemoryCandidate(
            MemoryScope.world(), MemoryVisibility.PUBLIC, new MemoryConfidence(0.4), new MemoryImportance(0.4), bounded
        );
        return CompletableFuture.completedFuture(new MemoryCompactionResult(List.of(), Optional.of(summary), Optional.of(situation)));
    }

    private static String truncate(String value, int limit) {
        if (value.codePointCount(0, value.length()) <= limit) return value;
        int end = value.offsetByCodePoints(0, limit - 1);
        return value.substring(0, end) + "…";
    }
}
