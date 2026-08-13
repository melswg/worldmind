package io.github.melswg.worldmind.gamecontext.internal;

import io.github.melswg.worldmind.api.gamecontext.v1.GameContextLimits;
import io.github.melswg.worldmind.api.gamecontext.v1.GameContextProvider;
import io.github.melswg.worldmind.api.gamecontext.v1.GameContextRegistrar;
import io.github.melswg.worldmind.api.gamecontext.v1.GameContextRegistration;
import io.github.melswg.worldmind.api.gamecontext.v1.GameContextSource;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Consumer;

/** Deterministic, owner-verified registry behind the v1 public registrar. */
public final class GameContextRegistrationCatalog {
    private final NavigableMap<GameContextSource, RegisteredProvider> registrations = new TreeMap<>();
    private final Consumer<RegisteredProvider> removed;
    private boolean closed;

    public GameContextRegistrationCatalog(Consumer<RegisteredProvider> removed) {
        this.removed = Objects.requireNonNull(removed, "removed");
    }

    public GameContextRegistrar registrarFor(String owningModId) {
        String owner = requireOwner(owningModId);
        return provider -> register(owner, provider);
    }

    public synchronized List<RegisteredProvider> activeProviders() {
        return List.copyOf(registrations.values());
    }

    public void closeAll() {
        List<RegisteredProvider> retired;
        synchronized (this) {
            if (closed) return;
            closed = true;
            retired = new ArrayList<>(registrations.values());
            registrations.clear();
            retired.forEach(RegisteredProvider::deactivate);
        }
        retired.forEach(removed);
    }

    private GameContextRegistration register(String owner, GameContextProvider provider) {
        Objects.requireNonNull(provider, "provider");
        GameContextSource source = Objects.requireNonNull(provider.source(), "provider.source()");
        if (!owner.equals(source.namespace())) {
            throw new IllegalArgumentException("Game-context source namespace must match the owning Fabric mod id.");
        }
        RegisteredProvider registered;
        synchronized (this) {
            if (closed) throw new IllegalStateException("Game-context registration is closed.");
            if (registrations.size() >= GameContextLimits.MAX_PROVIDERS) {
                throw new IllegalStateException("Worldmind has reached the v1 game-context provider limit.");
            }
            if (registrations.containsKey(source)) {
                throw new IllegalArgumentException("Duplicate game-context provider source: " + source.canonicalName());
            }
            registered = new RegisteredProvider(source, provider);
            registrations.put(source, registered);
        }
        return new RegistrationHandle(registered);
    }

    private void unregister(RegisteredProvider registered) {
        boolean removedHere;
        synchronized (this) {
            removedHere = registrations.remove(registered.source(), registered);
            if (removedHere) registered.deactivate();
        }
        if (removedHere) removed.accept(registered);
    }

    private static String requireOwner(String owner) {
        Objects.requireNonNull(owner, "owningModId");
        if (owner.isBlank()) throw new IllegalArgumentException("owningModId must not be blank.");
        return owner;
    }

    /** Package-visible immutable provider ownership used by the internal runtime. */
    static final class RegisteredProvider {
        private final GameContextSource source;
        private final GameContextProvider provider;
        private boolean active = true;

        private RegisteredProvider(GameContextSource source, GameContextProvider provider) {
            this.source = source;
            this.provider = provider;
        }

        GameContextSource source() {
            return source;
        }

        GameContextProvider provider() {
            return provider;
        }

        synchronized boolean active() {
            return active;
        }

        synchronized void deactivate() {
            active = false;
        }
    }

    private final class RegistrationHandle implements GameContextRegistration {
        private final RegisteredProvider registered;

        private RegistrationHandle(RegisteredProvider registered) {
            this.registered = registered;
        }

        @Override
        public GameContextSource source() {
            return registered.source();
        }

        @Override
        public boolean active() {
            return registered.active();
        }

        @Override
        public void close() {
            unregister(registered);
        }
    }
}
