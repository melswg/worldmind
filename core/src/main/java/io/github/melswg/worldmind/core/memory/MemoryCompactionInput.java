package io.github.melswg.worldmind.core.memory;

import io.github.melswg.worldmind.core.conversation.WorldIdentity;
import java.util.List;
import java.util.Objects;

/** Bounded persisted raw range offered to a provider-neutral compaction boundary. */
public record MemoryCompactionInput(WorldIdentity worldIdentity, DerivedMemoryProvenance provenance, List<CompactionSource> sources) {
    public MemoryCompactionInput {
        Objects.requireNonNull(worldIdentity, "worldIdentity");
        Objects.requireNonNull(provenance, "provenance");
        sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
        if (sources.isEmpty()) throw new IllegalArgumentException("sources must not be empty.");
        long expected = provenance.sourceRange().firstSequence();
        for (CompactionSource source : sources) {
            if (source.sequence() != expected++) throw new IllegalArgumentException("sources must exactly cover the provenance range.");
        }
        if (expected - 1 != provenance.sourceRange().lastSequence()) {
            throw new IllegalArgumentException("sources must exactly cover the provenance range.");
        }
    }
}
