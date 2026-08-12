package io.github.melswg.worldmind.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.github.melswg.worldmind.core.conversation.ConversationOutcome;
import io.github.melswg.worldmind.core.conversation.ConversationRefusal;
import io.github.melswg.worldmind.core.conversation.ProviderRequest;
import io.github.melswg.worldmind.core.conversation.RefusalCode;
import io.github.melswg.worldmind.core.conversation.SafeServerResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

class ConversationAcceptanceSeamTest {
    @Test
    void returnsASafeServerResponseAndRecordsTheStableProviderRequest() {
        WorldmindAcceptanceScenario scenario = WorldmindTestkit.scenario();
        scenario.languageModel().willRespondWith("The meadow is quiet.");
        scenario.clock().advanceBy(Duration.ofMinutes(5));
        SyntheticVanillaGameContext gameContext = SyntheticVanillaGameContext.overworld("acceptance-world")
            .atGameTime(6_000)
            .withWeather("rain");

        CompletionStage<ConversationOutcome> outcome = scenario.submit(scenario.normalizedRequest(
            UUID.fromString("6eac0fe2-1ce7-47fc-9fb7-cd98778b467a"),
            "Mira",
            "What is the weather like?",
            gameContext
        ));

        ProviderRequest providerRequest = scenario.languageModel().onlyReceivedRequest();
        assertEquals("What is the weather like?", providerRequest.message());
        assertEquals(gameContext.asUntrustedContext(), providerRequest.untrustedContext().get(0));
        assertEquals(Instant.EPOCH.plus(Duration.ofMinutes(5)), scenario.clock().instant());
        assertFalse(outcome.toCompletableFuture().isDone());

        scenario.serverScheduler().runUntilIdle();

        SafeServerResponse response = assertInstanceOf(
            SafeServerResponse.class,
            outcome.toCompletableFuture().join()
        );
        assertEquals("The meadow is quiet.", response.text());
    }

    @Test
    void returnsATypedRefusalWithoutChangingTheSeam() {
        WorldmindAcceptanceScenario scenario = WorldmindTestkit.scenario();
        scenario.languageModel().willRefuseWith(RefusalCode.PROVIDER_UNAVAILABLE);

        CompletionStage<ConversationOutcome> outcome = scenario.submit(scenario.normalizedRequest(
            UUID.fromString("2f952f26-5f3c-4d75-8dc4-5d89abbb0fea"),
            "Alex",
            "Are you there?",
            SyntheticVanillaGameContext.overworld("acceptance-world")
        ));
        scenario.serverScheduler().runUntilIdle();

        ConversationRefusal refusal = assertInstanceOf(
            ConversationRefusal.class,
            outcome.toCompletableFuture().join()
        );
        assertEquals(RefusalCode.PROVIDER_UNAVAILABLE, refusal.code());
    }
}
