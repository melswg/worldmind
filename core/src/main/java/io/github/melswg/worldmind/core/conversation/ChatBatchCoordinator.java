package io.github.melswg.worldmind.core.conversation;

import io.github.melswg.worldmind.core.configuration.ChatBatchingConfiguration;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Thread-safe, bounded per-world ownership of accepted public chat. At most
 * one batch is handed off and one next batch is retained for each world.
 */
public final class ChatBatchCoordinator implements AutoCloseable {
    private final ChatBatchingConfiguration configuration;
    private final DelayedScheduler delayedScheduler;
    private final SealedChatBatchConsumer consumer;
    private final Map<WorldIdentity, WorldState> worlds = new HashMap<>();
    private boolean closed;

    public ChatBatchCoordinator(
        ChatBatchingConfiguration configuration,
        String characterName,
        java.time.Clock clock,
        DelayedScheduler delayedScheduler,
        SealedChatBatchConsumer consumer
    ) {
        this(configuration, new CharacterNameAddressingDetector(characterName), clock, delayedScheduler, consumer);
    }

    public ChatBatchCoordinator(
        ChatBatchingConfiguration configuration,
        CharacterNameAddressingDetector addressingDetector,
        java.time.Clock clock,
        DelayedScheduler delayedScheduler,
        SealedChatBatchConsumer consumer
    ) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(addressingDetector, "addressingDetector");
        Objects.requireNonNull(clock, "clock");
        this.delayedScheduler = Objects.requireNonNull(delayedScheduler, "delayedScheduler");
        this.consumer = Objects.requireNonNull(consumer, "consumer");
    }

    /**
     * Admits one durably sequenced public player message.
     * It never waits for a timer or batch-consumer completion.
     */
    public ChatBatchAdmission observe(ObservedPublicChatMessage observed, WorldIdentity worldIdentity) {
        Objects.requireNonNull(observed, "observed");
        Objects.requireNonNull(worldIdentity, "worldIdentity");
        List<SealedChatBatch> handoffs = new ArrayList<>(1);
        ChatBatchAdmission admission;
        synchronized (this) {
            if (closed) {
                return ChatBatchAdmission.IGNORED_AFTER_CLOSE;
            }
            WorldState state = worlds.computeIfAbsent(worldIdentity, ignored -> new WorldState());
            if (state.inFlight != null && state.sealedNext != null) {
                return ChatBatchAdmission.REJECTED_CAPACITY;
            }

            PendingBatch pending = state.inFlight == null ? state.pending : state.nextPending;
            if (pending == null) {
                pending = new PendingBatch();
                if (state.inFlight == null) {
                    state.pending = pending;
                } else {
                    state.nextPending = pending;
                }
                scheduleDeadline(worldIdentity, state, pending);
            }

            if (observed.sequence() <= state.lastObservedSequence) {
                throw new IllegalArgumentException("Observed messages must have strictly increasing per-world sequences.");
            }
            state.lastObservedSequence = observed.sequence();
            pending.messages.add(observed);
            pending.estimatedInputSize = ChatBatchInputEstimator.saturatedAdd(
                pending.estimatedInputSize,
                ChatBatchInputEstimator.estimate(observed)
            );

            ChatBatchSealReason reason = sealReason(pending, observed);
            if (reason == null) {
                admission = ChatBatchAdmission.ACCEPTED_PENDING;
            } else {
                SealedChatBatch sealed = seal(worldIdentity, state, pending, reason);
                if (state.inFlight == null) {
                    state.inFlight = sealed;
                    handoffs.add(sealed);
                } else {
                    state.sealedNext = sealed;
                }
                admission = ChatBatchAdmission.SEALED_FOR_HANDOFF;
            }
        }
        handoffs.forEach(this::handoff);
        return admission;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (WorldState state : worlds.values()) {
            cancel(state.pending);
            cancel(state.nextPending);
        }
        worlds.clear();
    }

    /** Returns accounting only; no raw message, player, or context data is exposed. */
    public synchronized ChatBatchSnapshot snapshot() {
        int messages = 0;
        int batches = 0;
        for (WorldState state : worlds.values()) {
            if (state.pending != null) {
                batches++;
                messages += state.pending.messages.size();
            }
            if (state.nextPending != null) {
                batches++;
                messages += state.nextPending.messages.size();
            }
            if (state.sealedNext != null) {
                batches++;
            }
        }
        return new ChatBatchSnapshot(messages, batches, closed);
    }

    /**
     * Seals not-yet-handed-off work for a configuration retirement. The
     * currently handed-off batch remains owned by its caller, which can
     * terminally audit it after cancelling its asynchronous work.
     */
    public synchronized List<SealedChatBatch> retirePending() {
        if (closed) return List.of();
        List<SealedChatBatch> retired = new ArrayList<>();
        for (Map.Entry<WorldIdentity, WorldState> entry : worlds.entrySet()) {
            WorldState state = entry.getValue();
            if (state.pending != null) {
                retired.add(seal(entry.getKey(), state, state.pending, ChatBatchSealReason.CONFIGURATION_RELOAD));
            }
            if (state.nextPending != null) {
                retired.add(seal(entry.getKey(), state, state.nextPending, ChatBatchSealReason.CONFIGURATION_RELOAD));
            }
            if (state.sealedNext != null) {
                retired.add(state.sealedNext);
                state.sealedNext = null;
            }
        }
        return List.copyOf(retired);
    }

    private void scheduleDeadline(WorldIdentity worldIdentity, WorldState state, PendingBatch pending) {
        Object deadlineIdentity = new Object();
        pending.deadlineIdentity = deadlineIdentity;
        try {
            pending.deadline = delayedScheduler.schedule(
                Duration.ofMillis(configuration.maxWaitMillis()),
                () -> onDeadline(worldIdentity, state, pending, deadlineIdentity)
            );
        } catch (RuntimeException ignored) {
            pending.deadline = null;
        }
    }

    private void onDeadline(WorldIdentity worldIdentity, WorldState expectedState, PendingBatch expectedPending, Object deadlineIdentity) {
        List<SealedChatBatch> handoffs = new ArrayList<>(1);
        synchronized (this) {
            if (closed || worlds.get(worldIdentity) != expectedState || expectedPending.deadlineIdentity != deadlineIdentity) {
                return;
            }
            boolean primaryPending = expectedState.inFlight == null && expectedState.pending == expectedPending;
            boolean nextPending = expectedState.inFlight != null && expectedState.nextPending == expectedPending;
            if (!primaryPending && !nextPending) {
                return;
            }
            SealedChatBatch sealed = seal(worldIdentity, expectedState, expectedPending, ChatBatchSealReason.MAXIMUM_WAIT);
            if (expectedState.inFlight == null) {
                expectedState.inFlight = sealed;
                handoffs.add(sealed);
            } else {
                expectedState.sealedNext = sealed;
            }
        }
        handoffs.forEach(this::handoff);
    }

    private SealedChatBatch seal(
        WorldIdentity worldIdentity,
        WorldState state,
        PendingBatch pending,
        ChatBatchSealReason reason
    ) {
        cancel(pending);
        if (state.pending == pending) {
            state.pending = null;
        }
        if (state.nextPending == pending) {
            state.nextPending = null;
        }
        List<ObservedPublicChatMessage> messages = List.copyOf(pending.messages);
        List<UntrustedContext> currentContext = messages.get(messages.size() - 1).currentContext();
        return new SealedChatBatch(worldIdentity, messages, reason, currentContext);
    }

    private void handoff(SealedChatBatch batch) {
        CompletionStage<?> completion;
        try {
            completion = consumer.accept(batch);
            if (completion == null) {
                completion = CompletableFuture.failedFuture(
                    new IllegalStateException("Sealed chat batch consumer returned no completion stage.")
                );
            }
        } catch (RuntimeException failure) {
            completion = CompletableFuture.failedFuture(failure);
        }
        try {
            completion.whenComplete((ignored, failure) -> complete(batch));
        } catch (RuntimeException failure) {
            complete(batch);
        }
    }

    private void complete(SealedChatBatch completed) {
        SealedChatBatch nextHandoff = null;
        synchronized (this) {
            if (closed) {
                return;
            }
            WorldState state = worlds.get(completed.worldIdentity());
            if (state == null || state.inFlight != completed) {
                return;
            }
            state.inFlight = null;
            if (state.sealedNext != null) {
                nextHandoff = state.sealedNext;
                state.sealedNext = null;
                state.inFlight = nextHandoff;
            } else if (state.nextPending != null) {
                state.pending = state.nextPending;
                state.nextPending = null;
            }
        }
        if (nextHandoff != null) {
            handoff(nextHandoff);
        }
    }

    private ChatBatchSealReason sealReason(PendingBatch pending, ObservedPublicChatMessage latest) {
        if (latest.addressingSignal() != AddressingSignal.NONE) {
            return ChatBatchSealReason.ADDRESSING_SIGNAL;
        }
        if (pending.messages.size() >= configuration.maxMessages()) {
            return ChatBatchSealReason.MAXIMUM_MESSAGE_COUNT;
        }
        if (pending.estimatedInputSize >= configuration.maxEstimatedInputCharacters()) {
            return ChatBatchSealReason.MAXIMUM_ESTIMATED_INPUT_SIZE;
        }
        return null;
    }

    private static void cancel(PendingBatch pending) {
        if (pending != null && pending.deadline != null) {
            pending.deadline.cancel();
            pending.deadline = null;
        }
    }

    private static final class WorldState {
        private long lastObservedSequence;
        private PendingBatch pending;
        private SealedChatBatch inFlight;
        private PendingBatch nextPending;
        private SealedChatBatch sealedNext;
    }

    private static final class PendingBatch {
        private final List<ObservedPublicChatMessage> messages = new ArrayList<>();
        private long estimatedInputSize;
        private Object deadlineIdentity;
        private ScheduledWork deadline;
    }
}
