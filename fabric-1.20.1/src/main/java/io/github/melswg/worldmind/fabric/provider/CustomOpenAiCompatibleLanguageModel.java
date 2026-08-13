package io.github.melswg.worldmind.fabric.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.melswg.worldmind.core.configuration.GenerationParameters;
import io.github.melswg.worldmind.core.configuration.ProviderConfiguration;
import io.github.melswg.worldmind.core.conversation.LanguageModel;
import io.github.melswg.worldmind.core.conversation.LanguageModelResult;
import io.github.melswg.worldmind.core.conversation.ProviderFailure;
import io.github.melswg.worldmind.core.conversation.ProviderFailureKind;
import io.github.melswg.worldmind.core.conversation.PromptTrust;
import io.github.melswg.worldmind.core.conversation.ProviderRefusal;
import io.github.melswg.worldmind.core.conversation.ProviderRequest;
import io.github.melswg.worldmind.core.conversation.ProviderResponse;
import io.github.melswg.worldmind.core.conversation.RefusalCode;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * OpenAI-compatible Chat Completions transport. It serializes the stable core
 * request but does not expose Minecraft types, credentials, or JSON to core.
 */
public final class CustomOpenAiCompatibleLanguageModel implements LanguageModel {
    public static final String PROVIDER_ID = "custom-openai-compatible";
    private static final int MAX_RESPONSE_BYTES = 262_144;

    private final ProviderConfiguration configuration;
    private final HttpClient httpClient;
    private final ProviderCredentialResolver credentials;
    private final ChatCompletionsPromptRenderer promptRenderer = new ChatCompletionsPromptRenderer();

    public static CustomOpenAiCompatibleLanguageModel create(
        ProviderConfiguration configuration,
        ProviderCredentialResolver credentials
    ) {
        return new CustomOpenAiCompatibleLanguageModel(
            configuration,
            HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofMillis(configuration.timeouts().connectMillis()))
                .build(),
            credentials
        );
    }

    public CustomOpenAiCompatibleLanguageModel(
        ProviderConfiguration configuration,
        HttpClient httpClient,
        ProviderCredentialResolver credentials
    ) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        if (!PROVIDER_ID.equals(configuration.providerId())) {
            throw new IllegalArgumentException("Custom adapter requires provider id " + PROVIDER_ID + ".");
        }
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
    }

    @Override
    public CompletionStage<LanguageModelResult> complete(ProviderRequest providerRequest) {
        if (providerRequest == null) {
            return unavailable();
        }
        try {
            Optional<String> credential = credentials.resolveForOutgoingRequest(configuration.secretReference());
            if (credential.isEmpty() || credential.get().isBlank()) {
                return unavailable();
            }
            HttpRequest request = HttpRequest.newBuilder(configuration.endpoint().uri())
                .timeout(Duration.ofMillis(configuration.timeouts().responseCompletionMillis()))
                .header("Authorization", "Bearer " + credential.get())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(serializeRequest(providerRequest), StandardCharsets.UTF_8))
                .build();
            CompletableFuture<HttpResponse<String>> completion = httpClient.sendAsync(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            if (completion == null) {
                return unavailable();
            }
            return completion.handle(this::mapResponse);
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(failed(ProviderFailureKind.CONNECTION_FAILURE));
        }
    }

    private LanguageModelResult mapResponse(HttpResponse<String> response, Throwable failure) {
        if (failure != null) {
            return failed(failureKind(failure));
        }
        if (response == null) {
            return failed(ProviderFailureKind.CONNECTION_FAILURE);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return failed(httpFailure(response.statusCode()));
        }
        String responseBody = response.body();
        if (responseBody == null || responseBody.getBytes(StandardCharsets.UTF_8).length > MAX_RESPONSE_BYTES) {
            return failed(ProviderFailureKind.OVERSIZED_CONTENT);
        }
        try {
            JsonElement body = JsonParser.parseString(responseBody);
            if (!body.isJsonObject()) {
                return failed(ProviderFailureKind.MALFORMED_JSON);
            }
            JsonElement choicesElement = body.getAsJsonObject().get("choices");
            if (choicesElement == null || !choicesElement.isJsonArray() || choicesElement.getAsJsonArray().isEmpty()) {
                return failed(ProviderFailureKind.MALFORMED_JSON);
            }
            JsonElement choice = choicesElement.getAsJsonArray().get(0);
            if (!choice.isJsonObject()) {
                return failed(ProviderFailureKind.MALFORMED_JSON);
            }
            JsonElement message = choice.getAsJsonObject().get("message");
            if (message == null || !message.isJsonObject()) {
                return failed(ProviderFailureKind.MALFORMED_JSON);
            }
            JsonElement content = message.getAsJsonObject().get("content");
            if (content == null || !content.isJsonPrimitive() || !content.getAsJsonPrimitive().isString()) {
                return failed(ProviderFailureKind.MALFORMED_JSON);
            }
            String text = content.getAsString();
            return text.isBlank() ? failed(ProviderFailureKind.EMPTY_CONTENT) : new ProviderResponse(text);
        } catch (RuntimeException failureDuringMapping) {
            return failed(ProviderFailureKind.MALFORMED_JSON);
        }
    }

    private String serializeRequest(ProviderRequest providerRequest) {
        JsonObject body = new JsonObject();
        body.addProperty("model", providerRequest.model());
        addGenerationParameters(body, providerRequest.generationParameters());
        body.add("messages", serializeMessages(providerRequest));
        return body.toString();
    }

    private void addGenerationParameters(JsonObject body, GenerationParameters generation) {
        generation.temperature().ifPresent(value -> body.addProperty("temperature", value));
        generation.topP().ifPresent(value -> body.addProperty("top_p", value));
        generation.maxOutputTokens().ifPresent(value -> body.addProperty("max_tokens", value));
    }

    private JsonArray serializeMessages(ProviderRequest providerRequest) {
        JsonArray messages = new JsonArray();
        messages.add(message("system", renderLayers(providerRequest, PromptTrust.TRUSTED_INSTRUCTION)));
        messages.add(message("user", renderLayers(providerRequest, PromptTrust.UNTRUSTED_DATA)));
        return messages;
    }

    private JsonObject message(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }

    private String renderLayers(ProviderRequest providerRequest, PromptTrust trust) {
        return promptRenderer.renderLayers(providerRequest, trust);
    }

    private CompletionStage<LanguageModelResult> unavailable() {
        return CompletableFuture.completedFuture(new ProviderRefusal(RefusalCode.PROVIDER_UNAVAILABLE));
    }

    private ProviderFailure failed(ProviderFailureKind kind) {
        return new ProviderFailure(kind);
    }

    private ProviderFailureKind httpFailure(int status) {
        if (status == 401 || status == 403) return ProviderFailureKind.HTTP_AUTHENTICATION;
        if (status == 429) return ProviderFailureKind.HTTP_RATE_LIMITED;
        if (status >= 500 && status <= 599) return ProviderFailureKind.HTTP_SERVER_ERROR;
        return ProviderFailureKind.HTTP_NON_RETRYABLE;
    }

    private ProviderFailureKind failureKind(Throwable thrown) {
        Throwable failure = thrown;
        while ((failure instanceof java.util.concurrent.CompletionException
            || failure instanceof java.util.concurrent.ExecutionException) && failure.getCause() != null) {
            failure = failure.getCause();
        }
        if (failure instanceof java.net.http.HttpTimeoutException || failure instanceof java.util.concurrent.TimeoutException) {
            return ProviderFailureKind.TIMEOUT;
        }
        return ProviderFailureKind.CONNECTION_FAILURE;
    }
}
