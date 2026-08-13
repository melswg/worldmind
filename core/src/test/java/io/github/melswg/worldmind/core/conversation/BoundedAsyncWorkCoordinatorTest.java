package io.github.melswg.worldmind.core.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melswg.worldmind.core.configuration.RequestQueueConfiguration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BoundedAsyncWorkCoordinatorTest {
    private static final WorldIdentity FIRST_WORLD = new WorldIdentity("first-world");
    private static final WorldIdentity SECOND_WORLD = new WorldIdentity("second-world");

    @Test
    void boundsQueuedAndInFlightWorkPreservesWorldOrderAndReusesCapacityAfterTerminalPaths() throws Exception {
        BoundedAsyncWorkCoordinator coordinator = new BoundedAsyncWorkCoordinator(new RequestQueueConfiguration(1, 1));
        CompletableFuture<String> firstHeld = new CompletableFuture<>();
        CompletableFuture<String> secondHeld = new CompletableFuture<>();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        AtomicInteger starts = new AtomicInteger();

        var first = coordinator.submit(FIRST_WORLD, 1, AsyncWorkKind.CONVERSATION, () -> {
            starts.incrementAndGet(); firstStarted.countDown(); return firstHeld;
        });
        assertTrue(first.accepted());
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
        var second = coordinator.submit(FIRST_WORLD, 2, AsyncWorkKind.CONVERSATION, () -> {
            starts.incrementAndGet(); secondStarted.countDown(); return secondHeld;
        });
        assertTrue(second.accepted());
        assertEquals(new AsyncWorkSnapshot(1, 1, false), coordinator.snapshot());
        var overflow = coordinator.submit(SECOND_WORLD, 1, AsyncWorkKind.CONVERSATION, () -> CompletableFuture.completedFuture("never"));
        assertFalse(overflow.accepted());
        assertEquals(AsyncWorkRejection.CAPACITY, overflow.rejection().orElseThrow());

        firstHeld.complete("first");
        assertEquals("first", first.completion().toCompletableFuture().get(2, TimeUnit.SECONDS));
        assertTrue(secondStarted.await(2, TimeUnit.SECONDS));
        assertEquals(2, starts.get(), "the later same-world work starts only after the earlier completion");
        secondHeld.complete("second");
        assertEquals("second", second.completion().toCompletableFuture().get(2, TimeUnit.SECONDS));

        var reused = coordinator.submit(SECOND_WORLD, 1, AsyncWorkKind.CONVERSATION, () -> CompletableFuture.completedFuture("reused"));
        assertTrue(reused.accepted());
        assertEquals("reused", reused.completion().toCompletableFuture().get(2, TimeUnit.SECONDS));
        coordinator.close();
    }

    @Test
    void shutdownRejectsWaitingWorkWithoutLeakingCapacityOrStartingIt() throws Exception {
        BoundedAsyncWorkCoordinator coordinator = new BoundedAsyncWorkCoordinator(new RequestQueueConfiguration(2, 1));
        CompletableFuture<Void> held = new CompletableFuture<>();
        CountDownLatch started = new CountDownLatch(1);
        coordinator.submit(FIRST_WORLD, 1, AsyncWorkKind.CONVERSATION, () -> { started.countDown(); return held; });
        assertTrue(started.await(2, TimeUnit.SECONDS));
        AtomicInteger queuedStarts = new AtomicInteger();
        var queued = coordinator.submit(FIRST_WORLD, 2, AsyncWorkKind.CONVERSATION, () -> {
            queuedStarts.incrementAndGet(); return CompletableFuture.completedFuture(null);
        });
        coordinator.close();
        Throwable failure = org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.ExecutionException.class,
            () -> queued.completion().toCompletableFuture().get(2, TimeUnit.SECONDS)).getCause();
        AsyncWorkRejectedException rejection = assertInstanceOf(AsyncWorkRejectedException.class, failure);
        assertEquals(AsyncWorkRejection.CLOSED, rejection.rejection());
        assertEquals(0, queuedStarts.get());
        assertEquals(new AsyncWorkSnapshot(0, 0, true), coordinator.snapshot());
        assertTrue(coordinator.closeAsync().toCompletableFuture().isDone());
    }
}
