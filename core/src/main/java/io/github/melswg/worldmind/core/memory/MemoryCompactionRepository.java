package io.github.melswg.worldmind.core.memory;

import io.github.melswg.worldmind.core.conversation.WorldIdentity;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** Async storage boundary for provenance-preserving dialogue compaction. */
public interface MemoryCompactionRepository {
    CompletionStage<Optional<MemoryCompactionInput>> nextCompaction(WorldIdentity worldIdentity);
    CompletionStage<MemoryCompactionSnapshot> persistCompaction(MemoryCompactionInput input, MemoryCompactionResult result);
    CompletionStage<MemoryCompactionSnapshot> readCompactionSnapshot();
}
