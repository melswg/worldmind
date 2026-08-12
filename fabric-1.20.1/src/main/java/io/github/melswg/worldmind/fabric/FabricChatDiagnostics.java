package io.github.melswg.worldmind.fabric;

/** Receives only redaction-safe delivery diagnostics. */
@FunctionalInterface
interface FabricChatDiagnostics {
    void record(FabricChatDeliveryDiagnostic diagnostic);
}
