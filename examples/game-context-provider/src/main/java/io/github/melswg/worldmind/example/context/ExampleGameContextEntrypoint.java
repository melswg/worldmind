package io.github.melswg.worldmind.example.context;

import io.github.melswg.worldmind.api.gamecontext.v1.GameContextRegistrar;
import io.github.melswg.worldmind.api.gamecontext.v1.WorldmindGameContextEntrypoint;

/** Independently compilable external Fabric-mod entrypoint using only Worldmind's public v0.1 API. */
public final class ExampleGameContextEntrypoint implements WorldmindGameContextEntrypoint {
    @Override
    public void register(GameContextRegistrar registrar) {
        registrar.register(new ExampleSeasonContextProvider());
    }
}
