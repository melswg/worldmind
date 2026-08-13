package io.github.melswg.worldmind.testkit;

import io.github.melswg.worldmind.api.gamecontext.v1.GameContextSource;
import java.util.List;
import java.util.Objects;

/** Reusable data-driven inputs for runtime and Fabric acceptance contract tests. */
public final class GameContextProviderContractSuite {
    private GameContextProviderContractSuite() {
    }

    public static List<GameContextProviderContractCase> standardCases() {
        return List.of(GameContextProviderContractCase.values());
    }

    public static ScriptedGameContextProvider provider(
        GameContextSource source,
        GameContextProviderContractCase contractCase
    ) {
        return new ScriptedGameContextProvider(
            Objects.requireNonNull(source, "source"),
            Objects.requireNonNull(contractCase, "contractCase")
        );
    }
}
