package io.github.melswg.worldmind.core.administration;

import io.github.melswg.worldmind.core.configuration.IntegrationDisableReason;
import io.github.melswg.worldmind.core.conversation.ProviderCircuitSnapshot;
import java.util.Objects;
import java.util.Optional;

/**
 * Minecraft-independent operator status. It deliberately cannot carry
 * provider configuration, raw chat, player identity, filesystem locations,
 * exception text, or secret material.
 */
public record RuntimeStatusSnapshot(
    RuntimeLifecycleState lifecycle,
    RuntimeReloadState reload,
    boolean integrationEnabled,
    Optional<IntegrationDisableReason> disableReason,
    Optional<String> activeProfile,
    ProviderAvailability providerAvailability,
    Optional<ChatBatchingStatus> batching,
    WorkStatus work,
    Optional<ProviderCircuitSnapshot> circuit,
    StorageHealth storage,
    CompactionStatus compaction
) {
    public RuntimeStatusSnapshot {
        Objects.requireNonNull(lifecycle, "lifecycle");
        Objects.requireNonNull(reload, "reload");
        disableReason = optional(disableReason, "disableReason");
        activeProfile = optional(activeProfile, "activeProfile");
        Objects.requireNonNull(providerAvailability, "providerAvailability");
        batching = optional(batching, "batching");
        Objects.requireNonNull(work, "work");
        circuit = optional(circuit, "circuit");
        Objects.requireNonNull(storage, "storage");
        Objects.requireNonNull(compaction, "compaction");
        if (integrationEnabled && disableReason.isPresent()) {
            throw new IllegalArgumentException("Enabled integration has no disable reason.");
        }
    }

    private static <T> Optional<T> optional(Optional<T> value, String name) {
        return Optional.ofNullable(Objects.requireNonNull(value, name).orElse(null));
    }
}
