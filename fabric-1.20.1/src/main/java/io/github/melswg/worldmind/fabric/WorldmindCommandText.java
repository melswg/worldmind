package io.github.melswg.worldmind.fabric;

import io.github.melswg.worldmind.core.administration.RuntimeStatusSnapshot;
import io.github.melswg.worldmind.core.administration.MemoryAuditRecord;
import io.github.melswg.worldmind.core.configuration.ConfigurationDiagnostic;

/** Literal-only, redaction-aware command text. No Throwable or configuration value reaches this boundary. */
final class WorldmindCommandText {
    private WorldmindCommandText() { }

    static String status(RuntimeStatusSnapshot status) {
        String batching = status.batching().map(value -> " batch=" + value.pendingMessages() + "/" + value.pendingBatches()
            + " limits=" + value.maxMessages() + "/" + value.maxWaitMillis() + "/" + value.maxEstimatedInputCharacters()).orElse(" batch=unavailable");
        String circuit = status.circuit().map(value -> " circuit=" + value.state() + " failures="
            + value.consecutiveQualifyingFailures() + " cooldown=" + (value.cooldownUntil().isPresent() ? "active" : "none")
            + " probe=" + value.probeInFlight()).orElse(" circuit=unavailable");
        String extensions = status.gameContextExtensions().map(value -> " extensions=" + value.active() + "/"
            + value.registered() + " quarantined=" + value.quarantined() + " inFlight=" + value.inFlight()
            + value.latestProvider().map(provider -> " last=" + provider + ":" + value.latestDiagnosticCode().orElse("none")).orElse("")
        ).orElse(" extensions=unavailable");
        return "Worldmind lifecycle=" + status.lifecycle()
            + " reload=" + status.reload()
            + " integration=" + (status.integrationEnabled() ? "ENABLED" : "DISABLED")
            + status.activeProfile().map(value -> " profile=" + value).orElse("")
            + status.disableReason().map(value -> " disableReason=" + value).orElse("")
            + status.providerPresetId().map(value -> " preset=" + value).orElse(" preset=none")
            + " provider=" + status.providerAvailability()
            + " queue=" + status.work().queued() + "/" + status.work().inFlight() + "/" + status.work().closed()
            + " retry=" + status.work().retryAttempts() + "/" + status.work().waitingBackoff()
            + batching + circuit + extensions
            + " storage=" + status.storage()
            + " compaction=" + status.compaction().queued() + "/" + status.compaction().inFlight() + "/" + status.compaction().lastOutcome();
    }

    static String diagnostic(ConfigurationDiagnostic diagnostic) {
        // Loader-generated paths/reasons are fixed vocabulary. Strip any unexpected controls defensively.
        return "- " + clean(diagnostic.field()) + ": " + clean(diagnostic.reason());
    }

    static String memoryRecord(MemoryAuditRecord record) {
        String scope = record.actualScope().kind() == io.github.melswg.worldmind.core.administration.MemoryInspectionScope.Kind.WORLD
            ? "WORLD" : "PLAYER";
        String metadata = " id=" + record.stableIdentity() + " type=" + record.recordType()
            + " seq=" + record.firstSequence() + "-" + record.lastSequence()
            + " scope=" + scope + " visibility=" + record.visibility()
            + " source=" + record.sourceType() + " at=" + record.recordedAt();
        String state = record.state().map(value -> " state=" + value).orElse("")
            + record.confidence().map(value -> " confidence=" + value).orElse("")
            + record.importance().map(value -> " importance=" + value).orElse("")
            + record.version().map(value -> " version=" + value).orElse("")
            + record.latest().map(value -> " latest=" + value).orElse("")
            + " provenance=" + record.provenance().firstSequence() + "-" + record.provenance().lastSequence();
        String preview = record.text().isBlank() ? "" : " text=" + clean(record.text()) + (record.textTruncated() ? "…" : "");
        return "Worldmind memory" + metadata + state + preview;
    }

    private static String clean(String value) {
        StringBuilder result = new StringBuilder(Math.min(value.length(), 256));
        value.codePoints().filter(point -> point >= 0x20 && point != 0x7F && point != 0x202A && point != 0x202B
                && point != 0x202D && point != 0x202E && point != 0x2066 && point != 0x2067 && point != 0x2068 && point != 0x2069)
            .limit(256).forEach(result::appendCodePoint);
        return result.toString();
    }
}
