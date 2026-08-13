package io.github.melswg.worldmind.core.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class MemoryInspectionCursorTest {
    @Test
    void cursorIsOpaqueAndRejectedWhenItsScopeOrTypeChanges() {
        MemoryInspectionScope player = MemoryInspectionScope.player(UUID.fromString("6eac0fe2-1ce7-47fc-9fb7-cd98778b467a"));
        MemoryInspectionCursor cursor = new MemoryInspectionCursor(MemoryRecordType.FACT, player.fingerprint(), 12, 10, 9, "fact:abc");

        assertEquals(cursor, MemoryInspectionCursor.decode(cursor.encode(), MemoryRecordType.FACT, player));
        assertThrows(IllegalArgumentException.class, () ->
            MemoryInspectionCursor.decode(cursor.encode(), MemoryRecordType.EVENT, player));
        assertThrows(IllegalArgumentException.class, () ->
            MemoryInspectionCursor.decode(cursor.encode(), MemoryRecordType.FACT, MemoryInspectionScope.world()));
    }
}
