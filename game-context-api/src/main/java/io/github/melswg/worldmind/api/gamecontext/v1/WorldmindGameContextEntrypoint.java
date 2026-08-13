package io.github.melswg.worldmind.api.gamecontext.v1;

/**
 * Fabric custom entrypoint implemented by an external mod. Registration is
 * synchronous; providers must defer all work to their callback methods.
 */
@FunctionalInterface
public interface WorldmindGameContextEntrypoint {
    void register(GameContextRegistrar registrar);
}
