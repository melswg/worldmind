package io.github.melswg.worldmind.core.administration;

import java.util.concurrent.CompletionStage;

/** Storage boundary for a portable export; it exposes only bounded pages. */
public interface MemoryExportRepository {
    CompletionStage<MemoryExportPage> exportPage(MemoryExportQuery query);
}
