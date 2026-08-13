package io.github.melswg.worldmind.api.gamecontext.v1;

/** Registers one stable source of optional, untrusted game context. */
@FunctionalInterface
public interface GameContextRegistrar {
    GameContextRegistration register(GameContextProvider provider);
}
