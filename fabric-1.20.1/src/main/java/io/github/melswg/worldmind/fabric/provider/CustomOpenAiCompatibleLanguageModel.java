package io.github.melswg.worldmind.fabric.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.melswg.worldmind.core.configuration.GenerationParameters;
import io.github.melswg.worldmind.core.configuration.ProviderConfiguration;
import io.github.melswg.worldmind.core.conversation.LanguageModel;
import io.github.melswg.worldmind.core.conversation.LanguageModelResult;
import io.github.melswg.worldmind.core.conversation.PromptFragment;
import io.github.melswg.worldmind.core.conversation.PromptLayer;
import io.github.melswg.worldmind.core.conversation.PromptTrust;
import io.github.melswg.worldmind.core.conversation.ProviderRefusal;
import io.github.melswg.worldmind.core.conversation.ProviderRequest;
import io.github.melswg.worldmind.core.conversation.ProviderResponse;
import io.github.melswg.worldmind.core.conversation.RefusalCode;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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

    private final ProviderConfiguration configuration;
    private final HttpClient httpClient;
    private final ProviderCredentialResolver credentials;

    public static CustomOpenAiCompatibleLanguageModel create(
        ProviderConfiguration configuration,
        ProviderCredentialResolver credentials
    ) {
        return new CustomOpenAiCompatibleLanguageModel(
            configuration,
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
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
                .header("Authorization", "Bearer " + credential.get())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(serializeRequest(providerRequest), StandardCharsets.UTF_8))
                .build();
            CompletionStage<HttpResponse<String>> completion = httpClient.sendAsync(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            if (completion == null) {
                return unavailable();
            }
            return completion.handle(this::mapResponse);
        } catch (RuntimeException failure) {
            return unavailable();
        }
    }

    private LanguageModelResult mapResponse(HttpResponse<String> response, Throwable failure) {
        if (failure != null || response == null || response.statusCode() < 200 || response.statusCode() >= 300) {
            return new ProviderRefusal(RefusalCode.PROVIDER_UNAVAILABLE);
        }
        try {
            JsonElement body = JsonParser.parseString(response.body());
            if (!body.isJsonObject()) {
                return invalidResponse();
            }
            JsonElement choicesElement = body.getAsJsonObject().get("choices");
            if (choicesElement == null || !choicesElement.isJsonArray() || choicesElement.getAsJsonArray().isEmpty()) {
                return invalidResponse();
            }
            JsonElement choice = choicesElement.getAsJsonArray().get(0);
            if (!choice.isJsonObject()) {
                return invalidResponse();
            }
            JsonElement message = choice.getAsJsonObject().get("message");
            if (message == null || !message.isJsonObject()) {
                return invalidResponse();
            }
            JsonElement content = message.getAsJsonObject().get("content");
            if (content == null || !content.isJsonPrimitive() || !content.getAsJsonPrimitive().isString()) {
                return invalidResponse();
            }
            return new ProviderResponse(content.getAsString());
        } catch (RuntimeException failureDuringMapping) {
            return invalidResponse();
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
        StringBuilder rendered = new StringBuilder();
        providerRequest.promptLayers().stream()
            .filter(layer -> layer.trust() == trust)
            .forEach(layer -> appendLayer(rendered, layer));
        return rendered.toString();
    }

    private void appendLayer(StringBuilder rendered, PromptLayer layer) {
        rendered.append("<worldmind-layer type=\"").append(layer.type())
            .append("\" trust=\"").append(layer.trust()).append("\">\n");
        if (layer.fragments().isEmpty()) {
            rendered.append("<worldmind-empty/>\n");
        } else {
            for (PromptFragment fragment : layer.fragments()) {
                rendered.append("<worldmind-fragment source=\"").append(fragment.source()).append("\">\n")
                    .append(fragment.content()).append("\n</worldmind-fragment>\n");
            }
        }
        rendered.append("</worldmind-layer>\n");
    }

    private CompletionStage<LanguageModelResult> unavailable() {
        return CompletableFuture.completedFuture(new ProviderRefusal(RefusalCode.PROVIDER_UNAVAILABLE));
    }

    private ProviderRefusal invalidResponse() {
        return new ProviderRefusal(RefusalCode.INVALID_PROVIDER_RESPONSE);
    }
}
