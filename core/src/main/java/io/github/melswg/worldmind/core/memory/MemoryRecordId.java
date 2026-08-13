package io.github.melswg.worldmind.core.memory;

import java.util.Objects;
import java.util.UUID;

/** Stable opaque identifier for one durable world-memory record. */
public record MemoryRecordId(UUID value) {
    public MemoryRecordId {
        Objects.requireNonNull(value, "value");
    }
}
