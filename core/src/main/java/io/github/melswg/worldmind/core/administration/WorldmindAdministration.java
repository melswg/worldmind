package io.github.melswg.worldmind.core.administration;

import java.util.concurrent.CompletionStage;

/**
 * Reusable administration boundary. Fabric commands adapt this interface but
 * do not own configuration parsing, runtime mutation, or storage reads.
 */
public interface WorldmindAdministration {
    RuntimeStatusSnapshot status();

    CompletionStage<ConfigurationValidationReport> validate();

    CompletionStage<ReloadResult> reload();
}
