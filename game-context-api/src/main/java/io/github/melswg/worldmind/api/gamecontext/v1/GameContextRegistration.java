package io.github.melswg.worldmind.api.gamecontext.v1;

/** Handle for one provider registration. Closing it is idempotent. */
public interface GameContextRegistration extends AutoCloseable {
    GameContextSource source();

    boolean active();

    @Override
    void close();
}
