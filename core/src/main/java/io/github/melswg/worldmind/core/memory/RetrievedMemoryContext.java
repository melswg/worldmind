package io.github.melswg.worldmind.core.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Bounded three-part memory context assembled entirely as untrusted data. */
public record RetrievedMemoryContext(
    List<RetrievedMemoryEntry> recentDialogue,
    List<RetrievedMemoryEntry> currentSituations,
    List<RetrievedMemoryEntry> olderRecords
) {
    public static final int MAX_SERIALIZED_CODE_POINTS = 2_200;

    public RetrievedMemoryContext {
        recentDialogue = List.copyOf(Objects.requireNonNull(recentDialogue, "recentDialogue"));
        currentSituations = List.copyOf(Objects.requireNonNull(currentSituations, "currentSituations"));
        olderRecords = List.copyOf(Objects.requireNonNull(olderRecords, "olderRecords"));
        if (recentDialogue.size() > 12 || currentSituations.size() > 4 || olderRecords.size() > 12) {
            throw new IllegalArgumentException("Retrieved memory exceeds v1 count limits.");
        }
    }

    public List<RetrievedMemoryEntry> entriesInPromptOrder() {
        List<RetrievedMemoryEntry> result = new ArrayList<>(recentDialogue.size() + currentSituations.size() + olderRecords.size());
        result.addAll(recentDialogue);
        result.addAll(currentSituations);
        result.addAll(olderRecords);
        return List.copyOf(result);
    }

    public static RetrievedMemoryContext empty() {
        return new RetrievedMemoryContext(List.of(), List.of(), List.of());
    }
}
