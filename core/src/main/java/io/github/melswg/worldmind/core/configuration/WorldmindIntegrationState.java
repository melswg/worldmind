package io.github.melswg.worldmind.core.configuration;

/** The observable result of loading Worldmind startup configuration. */
public sealed interface WorldmindIntegrationState permits EnabledWorldmindIntegration, DisabledWorldmindIntegration {
}
