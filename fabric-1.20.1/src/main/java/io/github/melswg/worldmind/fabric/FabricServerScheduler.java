package io.github.melswg.worldmind.fabric;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.server.MinecraftServer;

/**
 * Returns asynchronous completions to the authoritative server thread while
 * dropping work that arrives after its logical-server runtime has stopped.
 */
final class FabricServerScheduler implements Executor, AutoCloseable {
    private final MinecraftServer server;
    private final AtomicBoolean active = new AtomicBoolean(true);

    FabricServerScheduler(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server");
    }

    @Override
    public void execute(Runnable command) {
        Objects.requireNonNull(command, "command");
        if (!active.get()) {
            return;
        }
        try {
            server.execute(() -> {
                if (active.get()) {
                    command.run();
                }
            });
        } catch (RuntimeException ignored) {
            // A stopping server cannot accept late provider completion work.
        }
    }

    boolean isActive() {
        return active.get();
    }

    @Override
    public void close() {
        active.set(false);
    }
}
