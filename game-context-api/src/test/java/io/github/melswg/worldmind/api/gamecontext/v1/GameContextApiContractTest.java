package io.github.melswg.worldmind.api.gamecontext.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class GameContextApiContractTest {
    @Test
    void publishesVersionedDependencyFreeDataTypesAndFixedV01Limits() {
        assertEquals("0.1", GameContextApi.VERSION);
        assertEquals("worldmind-game-context-v1", GameContextApi.ENTRYPOINT_KEY);
        assertEquals(32, GameContextLimits.MAX_PROVIDERS);
        assertEquals(500, GameContextLimits.CALLBACK_TIMEOUT_MILLIS);
        assertEquals(8, GameContextLimits.MAX_ENTRIES_PER_RESULT);

        assertTrue(Arrays.stream(GameContextRequest.class.getRecordComponents())
            .noneMatch(component -> component.getType().getName().startsWith("net.minecraft.")));
        assertTrue(Arrays.stream(GameContextRequest.class.getRecordComponents())
            .noneMatch(component -> component.getType().getName().equals("java.util.UUID")));
    }

    @Test
    void sourceIdentityIsCanonicalStableAndDoesNotAcceptSpoofableCasing() {
        GameContextSource first = new GameContextSource("another_mod", "season/context");
        GameContextSource second = new GameContextSource("another_mod", "zeta");

        assertEquals("another_mod:season/context", first.canonicalName());
        assertTrue(first.compareTo(second) < 0);
        assertNotEquals(first, second);
        assertThrows(IllegalArgumentException.class, () -> new GameContextSource("Another_Mod", "season"));
        assertThrows(IllegalArgumentException.class, () -> new GameContextSource("another_mod", "../season"));
    }

    @Test
    void resultIsDefensivelyImmutableAndMayBeEmpty() {
        GameContextResult empty = GameContextResult.empty();
        assertTrue(empty.entries().isEmpty());

        var entries = new java.util.ArrayList<GameContextEntry>();
        entries.add(new GameContextEntry("weather", "clear"));
        GameContextResult result = new GameContextResult(entries);
        entries.clear();
        assertEquals(1, result.entries().size());
        assertThrows(UnsupportedOperationException.class, () -> result.entries().add(new GameContextEntry("x", "y")));
        assertFalse(result.entries().isEmpty());
    }
}
