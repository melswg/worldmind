package io.github.melswg.worldmind.testkit;

import io.github.melswg.worldmind.core.conversation.ConversationApplicationService;
import io.github.melswg.worldmind.core.conversation.ConversationOutcome;
import io.github.melswg.worldmind.core.conversation.NormalizedServerRequest;
import io.github.melswg.worldmind.core.conversation.ProviderCapabilities;
import io.github.melswg.worldmind.core.conversation.ServerRequester;
import io.github.melswg.worldmind.core.conversation.UntrustedContext;
import io.github.melswg.worldmind.core.conversation.WorldIdentity;
import io.github.melswg.worldmind.core.configuration.ValidatedWorldmindConfiguration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Reusable high-level DSL for server-side Worldmind acceptance scenarios.
 */
public final class WorldmindAcceptanceScenario {
    private final FakeLanguageModel languageModel = new FakeLanguageModel();
    private final DeterministicScheduler serverScheduler = new DeterministicScheduler();
    private final ControlledClock clock = ControlledClock.startingAt(Instant.EPOCH);
    private final ConversationApplicationService applicationService =
        new ConversationApplicationService(languageModel, serverScheduler);

    public FakeLanguageModel languageModel() {
        return languageModel;
    }

    public DeterministicScheduler serverScheduler() {
        return serverScheduler;
    }

    public ControlledClock clock() {
        return clock;
    }

    public CompletionStage<ConversationOutcome> submit(NormalizedServerRequest request) {
        return applicationService.handle(request);
    }

    public NormalizedServerRequest normalizedRequest(
        UUID playerId,
        String playerName,
        String message,
        WorldIdentity worldIdentity,
        SyntheticVanillaGameContext vanillaContext,
        ValidatedWorldmindConfiguration validatedConfiguration,
        ProviderCapabilities providerCapabilities
    ) {
        List<UntrustedContext> context = new ArrayList<>();
        if (vanillaContext != null) {
            context.add(vanillaContext.asUntrustedContext());
        }
        return new NormalizedServerRequest(
            new ServerRequester(playerId, playerName),
            worldIdentity,
            message,
            context,
            validatedConfiguration,
            providerCapabilities
        );
    }
}
