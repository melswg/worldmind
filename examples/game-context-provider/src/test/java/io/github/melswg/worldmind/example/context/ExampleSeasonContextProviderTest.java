package io.github.melswg.worldmind.example.context;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.melswg.worldmind.api.gamecontext.v1.GameContextRequest;
import io.github.melswg.worldmind.api.gamecontext.v1.GameContextServerContext;
import io.github.melswg.worldmind.api.gamecontext.v1.GameContextWorldContext;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExampleSeasonContextProviderTest {
    @Test
    void producesStructuredSourceAttributedContextAndClearsLifecycleState() {
        ExampleSeasonContextProvider provider = new ExampleSeasonContextProvider();
        GameContextServerContext server = new GameContextServerContext("example-server");
        provider.onWorldLoad(new GameContextWorldContext(server, "minecraft:the_nether")).toCompletableFuture().join();
        provider.onWorldLoad(new GameContextWorldContext(server, "minecraft:overworld")).toCompletableFuture().join();

        assertEquals("worldmind-game-context-example:season", provider.source().canonicalName());
        assertEquals(List.of("loaded-dimensions", "season"), provider.provide(request(server)).toCompletableFuture().join().entries()
            .stream().map(entry -> entry.key()).sorted().toList());
        assertEquals("minecraft:overworld,minecraft:the_nether", provider.provide(request(server)).toCompletableFuture().join().entries()
            .stream().filter(entry -> entry.key().equals("loaded-dimensions")).findFirst().orElseThrow().value());

        provider.onCleanup().toCompletableFuture().join();
        assertEquals("", provider.provide(request(server)).toCompletableFuture().join().entries()
            .stream().filter(entry -> entry.key().equals("loaded-dimensions")).findFirst().orElseThrow().value());
    }

    private static GameContextRequest request(GameContextServerContext server) {
        return new GameContextRequest(server, "opaque-world", 1, 1, 1, Instant.EPOCH);
    }
}
