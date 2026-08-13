package io.github.melswg.worldmind.fabric.provider;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.melswg.worldmind.core.administration.ProviderAvailability;
import io.github.melswg.worldmind.core.configuration.ProviderConfiguration;
import io.github.melswg.worldmind.core.configuration.SecretAvailability;
import io.github.melswg.worldmind.core.conversation.LanguageModel;
import io.github.melswg.worldmind.core.conversation.LanguageModelResult;
import io.github.melswg.worldmind.core.conversation.ProviderFailure;
import io.github.melswg.worldmind.core.conversation.ProviderFailureKind;
import io.github.melswg.worldmind.core.conversation.ProviderRefusal;
import io.github.melswg.worldmind.core.conversation.ProviderRequest;
import io.github.melswg.worldmind.core.conversation.RefusalCode;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

/** One asynchronous, cancellable transport for all built-in Chat Completions presets. */
final class ChatCompletionsLanguageModel implements LanguageModel {
    private static final int MAX_RESPONSE_BYTES = 262_144;
    private final ProviderConfiguration configuration;
    private final ProviderPresetDescriptor descriptor;
    private final java.net.URI endpoint;
    private final HttpClient httpClient;
    private final ProviderCredentialResolver credentials;
    private final ChatCompletionsPromptRenderer promptRenderer = new ChatCompletionsPromptRenderer();
    private final AtomicReference<ProviderAvailability> availability;

    ChatCompletionsLanguageModel(
        ProviderConfiguration configuration,
        ProviderPresetDescriptor descriptor,
        java.net.URI endpoint,
        HttpClient httpClient,
        ProviderCredentialResolver credentials,
        AtomicReference<ProviderAvailability> availability
    ) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.availability = Objects.requireNonNull(availability, "availability");
    }

    @Override
    public CompletionStage<LanguageModelResult> complete(ProviderRequest request) {
        if (request == null) return unavailable(SecretAvailability.MISSING);
        ProviderCredentialResolution credential = credentials.resolveForOutgoingRequestResult(configuration.secretReference());
        if (credential.credential().isEmpty()) return unavailable(credential.availability());
        try {
            HttpRequest outgoing = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofMillis(configuration.timeouts().responseCompletionMillis()))
                .header("Authorization", "Bearer " + credential.credential().orElseThrow().authorizationValue())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(descriptor.serialize(request, promptRenderer), StandardCharsets.UTF_8))
                .build();
            CompletableFuture<HttpResponse<String>> wire = httpClient.sendAsync(outgoing, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (wire == null) return CompletableFuture.completedFuture(new ProviderFailure(ProviderFailureKind.CONNECTION_FAILURE));
            CompletableFuture<LanguageModelResult> mapped = new CompletableFuture<>();
            wire.whenComplete((response, failure) -> mapped.complete(mapResponse(response, failure)));
            mapped.whenComplete((ignored, failure) -> {
                if (mapped.isCancelled()) wire.cancel(true);
            });
            return mapped;
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(new ProviderFailure(ProviderFailureKind.CONNECTION_FAILURE));
        }
    }

    private CompletionStage<LanguageModelResult> unavailable(SecretAvailability secretAvailability) {
        availability.set(switch (secretAvailability) {
            case MISSING -> ProviderAvailability.SECRET_MISSING;
            case UNREADABLE -> ProviderAvailability.SECRET_UNREADABLE;
            case REJECTED -> ProviderAvailability.CREDENTIAL_REJECTED;
            case AVAILABLE -> ProviderAvailability.NOT_READY;
        });
        return CompletableFuture.completedFuture(new ProviderRefusal(RefusalCode.PROVIDER_UNAVAILABLE));
    }

    private LanguageModelResult mapResponse(HttpResponse<String> response, Throwable failure) {
        if (failure != null) {
            if (isCancellation(failure)) return new ProviderFailure(ProviderFailureKind.CANCELLED);
            return new ProviderFailure(failureKind(failure));
        }
        if (response == null) return new ProviderFailure(ProviderFailureKind.CONNECTION_FAILURE);
        String body = response.body();
        if (body == null || body.getBytes(StandardCharsets.UTF_8).length > MAX_RESPONSE_BYTES) {
            return new ProviderFailure(ProviderFailureKind.OVERSIZED_CONTENT);
        }
        Optional<JsonObject> parsed = parseObject(body);
        LanguageModelResult result;
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            result = descriptor.decodeFailure(response.statusCode(), response.headers(), parsed);
        } else if (parsed.isEmpty()) {
            result = new ProviderFailure(ProviderFailureKind.MALFORMED_JSON);
        } else {
            result = descriptor.decodeSuccess(parsed.orElseThrow());
        }
        if (result instanceof ProviderFailure providerFailure && providerFailure.kind() == ProviderFailureKind.HTTP_AUTHENTICATION) {
            availability.set(ProviderAvailability.AUTHENTICATION_FAILED);
        } else if (!(result instanceof ProviderFailure)) {
            availability.set(ProviderAvailability.READY);
        }
        return result;
    }

    private Optional<JsonObject> parseObject(String body) {
        try {
            JsonElement element = JsonParser.parseString(body);
            return element.isJsonObject() ? Optional.of(element.getAsJsonObject()) : Optional.empty();
        } catch (RuntimeException ignored) { return Optional.empty(); }
    }

    private ProviderFailureKind failureKind(Throwable thrown) {
        Throwable failure = thrown;
        while ((failure instanceof CompletionException || failure instanceof ExecutionException) && failure.getCause() != null) failure = failure.getCause();
        if (failure instanceof java.net.http.HttpTimeoutException || failure instanceof java.util.concurrent.TimeoutException) return ProviderFailureKind.TIMEOUT;
        return ProviderFailureKind.CONNECTION_FAILURE;
    }

    private boolean isCancellation(Throwable thrown) {
        Throwable failure = thrown;
        while ((failure instanceof CompletionException || failure instanceof ExecutionException) && failure.getCause() != null) failure = failure.getCause();
        return failure instanceof java.util.concurrent.CancellationException;
    }
}
