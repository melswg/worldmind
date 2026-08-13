package io.github.melswg.worldmind.core.memory;

import java.util.concurrent.CompletionStage;

/** Provider-neutral asynchronous boundary for one bounded raw compaction range. */
@FunctionalInterface
public interface MemoryCompactionGenerator {
    CompletionStage<MemoryCompactionResult> compact(MemoryCompactionInput input);
}
