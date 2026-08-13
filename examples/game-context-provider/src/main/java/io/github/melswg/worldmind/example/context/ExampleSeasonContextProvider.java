package io.github.melswg.worldmind.example.context;

import io.github.melswg.worldmind.api.gamecontext.v1.GameContextEntry;
import io.github.melswg.worldmind.api.gamecontext.v1.GameContextProvider;
import io.github.melswg.worldmind.api.gamecontext.v1.GameContextRequest;
import io.github.melswg.worldmind.api.gamecontext.v1.GameContextResult;
import io.github.melswg.worldmind.api.gamecontext.v1.GameContextSource;
import io.github.melswg.worldmind.api.gamecontext.v1.GameContextWorldContext;
import java.util.List;
import java.util.NavigableSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentSkipListSet;

/** Shows lifecycle-scoped state and a source-attributed structured result without server handles or private APIs. */
final class ExampleSeasonContextProvider implements GameContextProvider {
    private static final GameContextSource SOURCE = new GameContextSource("worldmind-game-context-example", "season");
    private final NavigableSet<String> loadedDimensions = new ConcurrentSkipListSet<>();

    @Override
    public GameContextSource source() {
        return SOURCE;
    }

    @Override
    public CompletionStage<Void> onWorldLoad(GameContextWorldContext world) {
        loadedDimensions.add(world.dimensionId());
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Void> onWorldUnload(GameContextWorldContext world) {
        loadedDimensions.remove(world.dimensionId());
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<GameContextResult> provide(GameContextRequest request) {
        return CompletableFuture.completedFuture(new GameContextResult(List.of(
            new GameContextEntry("season", "demo"),
            new GameContextEntry("loaded-dimensions", String.join(",", loadedDimensions))
        )));
    }

    @Override
    public CompletionStage<Void> onCleanup() {
        loadedDimensions.clear();
        return CompletableFuture.completedFuture(null);
    }
}
