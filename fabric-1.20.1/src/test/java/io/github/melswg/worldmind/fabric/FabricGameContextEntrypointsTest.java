package io.github.melswg.worldmind.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.melswg.worldmind.api.gamecontext.v1.GameContextEntry;
import io.github.melswg.worldmind.api.gamecontext.v1.GameContextProvider;
import io.github.melswg.worldmind.api.gamecontext.v1.GameContextRegistrar;
import io.github.melswg.worldmind.api.gamecontext.v1.GameContextRequest;
import io.github.melswg.worldmind.api.gamecontext.v1.GameContextResult;
import io.github.melswg.worldmind.api.gamecontext.v1.GameContextServerContext;
import io.github.melswg.worldmind.api.gamecontext.v1.GameContextSource;
import io.github.melswg.worldmind.core.conversation.AddressingSignal;
import io.github.melswg.worldmind.core.conversation.ChatBatchSealReason;
import io.github.melswg.worldmind.core.conversation.ObservedPublicChatMessage;
import io.github.melswg.worldmind.core.conversation.SealedChatBatch;
import io.github.melswg.worldmind.core.conversation.ServerRequester;
import io.github.melswg.worldmind.core.conversation.UntrustedContext;
import io.github.melswg.worldmind.core.conversation.WorldIdentity;
import io.github.melswg.worldmind.gamecontext.internal.GameContextExtensionRuntime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class FabricGameContextEntrypointsTest {
    @Test
    void registersExternalEntrypointsInOwningModOrderAndRejectsSpoofedSources() {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        GameContextExtensionRuntime runtime = new GameContextExtensionRuntime(
            worker, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
        );
        try {
            List<String> registrations = new ArrayList<>();
            FabricGameContextEntrypoints.registerAll(runtime, List.of(
                new FabricGameContextEntrypoints.DeclaredEntrypoint("zeta", registrar -> register(registrar, "zeta", registrations)),
                new FabricGameContextEntrypoints.DeclaredEntrypoint("alpha", registrar -> register(registrar, "alpha", registrations)),
                new FabricGameContextEntrypoints.DeclaredEntrypoint("spoofed", registrar -> register(registrar, "not_spoofed", registrations))
            ));

            assertEquals(List.of("alpha", "zeta"), registrations);
            runtime.onServerStart(new GameContextServerContext("test-server")).toCompletableFuture().join();
            assertEquals(List.of("alpha", "zeta"), runtime.resolve(batch()).toCompletableFuture().join()
                .stream().skip(1).map(context -> context.content()).toList());
        } finally {
            runtime.shutdown().toCompletableFuture().join();
            worker.shutdownNow();
        }
    }

    private static void register(GameContextRegistrar registrar, String source, List<String> registrations) {
        registrar.register(new GameContextProvider() {
            private final GameContextSource identity = new GameContextSource(source, "state");

            @Override
            public GameContextSource source() {
                return identity;
            }

            @Override
            public CompletableFuture<GameContextResult> provide(GameContextRequest request) {
                return CompletableFuture.completedFuture(new GameContextResult(List.of(
                    new GameContextEntry("state", source)
                )));
            }
        });
        registrations.add(source);
    }

    private static SealedChatBatch batch() {
        return new SealedChatBatch(new WorldIdentity("test-world"), List.of(new ObservedPublicChatMessage(
            1,
            new ServerRequester(UUID.fromString("bd540d44-a1c2-48b6-88e5-894c03ff4e5f"), "Mira"),
            "Aster, are you there?",
            AddressingSignal.EXACT,
            Instant.EPOCH,
            List.of(new UntrustedContext("vanilla-game-context", "clear"))
        )), ChatBatchSealReason.ADDRESSING_SIGNAL, List.of(new UntrustedContext("vanilla-game-context", "clear")));
    }
}
