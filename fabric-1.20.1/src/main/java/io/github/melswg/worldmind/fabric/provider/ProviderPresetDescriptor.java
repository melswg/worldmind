package io.github.melswg.worldmind.fabric.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.melswg.worldmind.core.configuration.GenerationParameters;
import io.github.melswg.worldmind.core.configuration.ProviderConfiguration;
import io.github.melswg.worldmind.core.configuration.ProviderEndpoint;
import io.github.melswg.worldmind.core.conversation.LanguageModelResult;
import io.github.melswg.worldmind.core.conversation.ProviderCapabilities;
import io.github.melswg.worldmind.core.conversation.ProviderFailure;
import io.github.melswg.worldmind.core.conversation.ProviderFailureKind;
import io.github.melswg.worldmind.core.conversation.ProviderRefusal;
import io.github.melswg.worldmind.core.conversation.ProviderRequest;
import io.github.melswg.worldmind.core.conversation.ProviderResponse;
import io.github.melswg.worldmind.core.conversation.PromptTrust;
import io.github.melswg.worldmind.core.conversation.RefusalCode;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Immutable preset metadata and deliberately narrow Chat Completions strategies. */
final class ProviderPresetDescriptor {
    static final String CUSTOM = "custom-openai-compatible";
    static final String OPENROUTER = "openrouter";
    static final String DEEPSEEK_DIRECT = "deepseek-direct";
    static final URI OPENROUTER_ENDPOINT = URI.create("https://openrouter.ai/api/v1/chat/completions");
    static final URI DEEPSEEK_ENDPOINT = URI.create("https://api.deepseek.com/chat/completions");
    private static final Pattern OPENROUTER_MODEL = Pattern.compile("~?[A-Za-z0-9][A-Za-z0-9._-]*/[A-Za-z0-9][A-Za-z0-9._-]*(?::[A-Za-z0-9][A-Za-z0-9._-]*)?");

    private final String id;
    private final Optional<URI> canonicalEndpoint;
    private final ProviderCapabilities capabilities;
    private final RequestMapper requestMapper;
    private final ResponseDecoder responseDecoder;
    private final HttpFailureClassifier failureClassifier;
    private final ModelValidator modelValidator;

    private ProviderPresetDescriptor(
        String id,
        Optional<URI> canonicalEndpoint,
        RequestMapper requestMapper,
        ResponseDecoder responseDecoder,
        HttpFailureClassifier failureClassifier,
        ModelValidator modelValidator
    ) {
        this.id = Objects.requireNonNull(id, "id");
        this.canonicalEndpoint = Objects.requireNonNull(canonicalEndpoint, "canonicalEndpoint");
        this.capabilities = new ProviderCapabilities(true);
        this.requestMapper = Objects.requireNonNull(requestMapper, "requestMapper");
        this.responseDecoder = Objects.requireNonNull(responseDecoder, "responseDecoder");
        this.failureClassifier = Objects.requireNonNull(failureClassifier, "failureClassifier");
        this.modelValidator = Objects.requireNonNull(modelValidator, "modelValidator");
    }

    static ProviderPresetDescriptor custom() {
        return new ProviderPresetDescriptor(CUSTOM, Optional.empty(),
            (request, renderer) -> baseRequest(request, renderer, "max_tokens", false, false),
            ProviderPresetDescriptor::customSuccess,
            (status, headers, body) -> standardFailure(status, true, body, false),
            (model, generation) -> Optional.empty());
    }

    static ProviderPresetDescriptor openRouter() {
        return new ProviderPresetDescriptor(OPENROUTER, Optional.of(OPENROUTER_ENDPOINT),
            (request, renderer) -> baseRequest(request, renderer, "max_completion_tokens", true, false),
            ProviderPresetDescriptor::openRouterSuccess,
            ProviderPresetDescriptor::openRouterFailure,
            (model, generation) -> {
                if (model.length() > 256 || !OPENROUTER_MODEL.matcher(model).matches()) {
                    return Optional.of("model must use OpenRouter author/model[:variant] syntax.");
                }
                return Optional.empty();
            });
    }

    static ProviderPresetDescriptor deepSeek() {
        return new ProviderPresetDescriptor(DEEPSEEK_DIRECT, Optional.of(DEEPSEEK_ENDPOINT),
            (request, renderer) -> baseRequest(request, renderer, "max_tokens", true, true),
            ProviderPresetDescriptor::deepSeekSuccess,
            (status, headers, body) -> {
                if (status == 400 || status == 422) return new ProviderFailure(ProviderFailureKind.INCOMPATIBLE_MODEL_OR_PARAMETER);
                if (status == 401) return new ProviderFailure(ProviderFailureKind.HTTP_AUTHENTICATION);
                if (status == 429) return new ProviderFailure(ProviderFailureKind.HTTP_RATE_LIMITED);
                if (status >= 500 && status <= 599) return new ProviderFailure(ProviderFailureKind.HTTP_SERVER_ERROR);
                return new ProviderFailure(ProviderFailureKind.HTTP_NON_RETRYABLE);
            },
            (model, generation) -> {
                if (!Set.of("deepseek-v4-flash", "deepseek-v4-pro").contains(model)) {
                    return Optional.of("model must be a supported direct DeepSeek model.");
                }
                if (generation.maxOutputTokens().filter(value -> value > 393_216).isPresent()) {
                    return Optional.of("maxOutputTokens must not exceed the direct DeepSeek maximum output.");
                }
                return Optional.empty();
            });
    }

    String id() { return id; }
    ProviderCapabilities capabilities() { return capabilities; }

    Optional<String> validate(ProviderConfiguration configuration) {
        if (canonicalEndpoint.isPresent() && configuration.endpoint().isPresent()) {
            return Optional.of("endpoint is forbidden for a built-in provider preset.");
        }
        if (canonicalEndpoint.isEmpty() && configuration.endpoint().isEmpty()) {
            return Optional.of("endpoint is required for the custom OpenAI-compatible provider.");
        }
        return modelValidator.validate(configuration.model(), configuration.generationParameters());
    }

    URI resolveEndpoint(ProviderConfiguration configuration) {
        return canonicalEndpoint.orElseGet(() -> configuration.endpoint().orElseThrow(
            () -> new IllegalArgumentException("Custom provider endpoint was not validated.")
        ).uri());
    }

    String serialize(ProviderRequest request, ChatCompletionsPromptRenderer renderer) {
        return requestMapper.serialize(request, renderer);
    }

    LanguageModelResult decodeSuccess(JsonObject body) {
        return responseDecoder.decode(body);
    }

    ProviderFailure decodeFailure(int status, HttpHeaders headers, Optional<JsonObject> body) {
        return failureClassifier.classify(status, headers, body);
    }

    private static String baseRequest(
        ProviderRequest request,
        ChatCompletionsPromptRenderer renderer,
        String outputLimitField,
        boolean explicitNonStreaming,
        boolean thinkingDisabled
    ) {
        JsonObject body = new JsonObject();
        body.addProperty("model", request.model());
        GenerationParameters generation = request.generationParameters();
        generation.temperature().ifPresent(value -> body.addProperty("temperature", value));
        generation.topP().ifPresent(value -> body.addProperty("top_p", value));
        generation.maxOutputTokens().ifPresent(value -> body.addProperty(outputLimitField, value));
        if (explicitNonStreaming) body.addProperty("stream", false);
        if (thinkingDisabled) {
            JsonObject thinking = new JsonObject();
            thinking.addProperty("type", "disabled");
            body.add("thinking", thinking);
        }
        JsonArray messages = new JsonArray();
        messages.add(message("system", renderer.renderLayers(request, PromptTrust.TRUSTED_INSTRUCTION)));
        messages.add(message("user", renderer.renderLayers(request, PromptTrust.UNTRUSTED_DATA)));
        body.add("messages", messages);
        return body.toString();
    }

    private static JsonObject message(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }

    private static LanguageModelResult openAiCompatibleSuccess(JsonObject body) {
        Choice choice = firstChoice(body);
        if (choice == null) return new ProviderFailure(ProviderFailureKind.MALFORMED_JSON);
        if (!"assistant".equals(choice.role())) return new ProviderFailure(ProviderFailureKind.MALFORMED_RESPONSE);
        if ("content_filter".equals(choice.finishReason()) || choice.refusal()) return new ProviderRefusal(RefusalCode.PROVIDER_REFUSED);
        if ("length".equals(choice.finishReason())) return new ProviderFailure(ProviderFailureKind.INCOMPATIBLE_MODEL_OR_PARAMETER);
        if ("tool_calls".equals(choice.finishReason())) return new ProviderFailure(ProviderFailureKind.MALFORMED_RESPONSE);
        if (choice.content() == null) return new ProviderFailure(ProviderFailureKind.MALFORMED_JSON);
        return choice.content().isBlank() ? new ProviderFailure(ProviderFailureKind.EMPTY_CONTENT) : new ProviderResponse(choice.content());
    }

    /** Preserves the original custom OpenAI-compatible success contract exactly. */
    private static LanguageModelResult customSuccess(JsonObject body) {
        Choice choice = firstChoice(body);
        if (choice == null || choice.content() == null) return new ProviderFailure(ProviderFailureKind.MALFORMED_JSON);
        return choice.content().isBlank() ? new ProviderFailure(ProviderFailureKind.EMPTY_CONTENT) : new ProviderResponse(choice.content());
    }

    private static LanguageModelResult openRouterSuccess(JsonObject body) {
        JsonElement topLevelError = body.get("error");
        if (topLevelError != null && topLevelError.isJsonObject()) {
            return failureFromOpenRouterError(topLevelError.getAsJsonObject(), HttpHeaders.of(java.util.Map.of(), (a, b) -> true));
        }
        Choice choice = firstChoice(body);
        if (choice == null) return new ProviderFailure(ProviderFailureKind.MALFORMED_JSON);
        if (!"assistant".equals(choice.role())) return new ProviderFailure(ProviderFailureKind.MALFORMED_RESPONSE);
        if ("error".equals(choice.finishReason())) {
            JsonObject rawChoice = body.getAsJsonArray("choices").get(0).getAsJsonObject();
            JsonElement error = rawChoice.get("error");
            return error != null && error.isJsonObject()
                ? failureFromOpenRouterError(error.getAsJsonObject(), HttpHeaders.of(java.util.Map.of(), (a, b) -> true))
                : new ProviderFailure(ProviderFailureKind.MALFORMED_RESPONSE);
        }
        return openAiCompatibleSuccess(body);
    }

    private static LanguageModelResult deepSeekSuccess(JsonObject body) {
        Choice choice = firstChoice(body);
        if (choice == null) return new ProviderFailure(ProviderFailureKind.MALFORMED_JSON);
        if (!"assistant".equals(choice.role())) return new ProviderFailure(ProviderFailureKind.MALFORMED_RESPONSE);
        return switch (choice.finishReason()) {
            case "stop" -> choice.content() == null ? new ProviderFailure(ProviderFailureKind.MALFORMED_RESPONSE)
                : choice.content().isBlank() ? new ProviderFailure(ProviderFailureKind.EMPTY_CONTENT) : new ProviderResponse(choice.content());
            case "content_filter" -> new ProviderRefusal(RefusalCode.PROVIDER_REFUSED);
            case "insufficient_system_resource" -> new ProviderFailure(ProviderFailureKind.HTTP_SERVER_ERROR);
            case "length" -> new ProviderFailure(ProviderFailureKind.INCOMPATIBLE_MODEL_OR_PARAMETER);
            case "tool_calls" -> new ProviderFailure(ProviderFailureKind.MALFORMED_RESPONSE);
            default -> new ProviderFailure(ProviderFailureKind.MALFORMED_RESPONSE);
        };
    }

    private static Choice firstChoice(JsonObject body) {
        JsonElement choices = body.get("choices");
        if (choices == null || !choices.isJsonArray() || choices.getAsJsonArray().isEmpty()) return null;
        JsonElement first = choices.getAsJsonArray().get(0);
        if (!first.isJsonObject()) return null;
        JsonObject choice = first.getAsJsonObject();
        JsonElement message = choice.get("message");
        if (message == null || !message.isJsonObject()) return null;
        JsonObject messageObject = message.getAsJsonObject();
        JsonElement role = messageObject.get("role");
        String messageRole = role != null && role.isJsonPrimitive() && role.getAsJsonPrimitive().isString() ? role.getAsString() : null;
        JsonElement content = messageObject.get("content");
        String text = content != null && content.isJsonPrimitive() && content.getAsJsonPrimitive().isString() ? content.getAsString() : null;
        JsonElement finish = choice.get("finish_reason");
        String reason = finish != null && finish.isJsonPrimitive() && finish.getAsJsonPrimitive().isString() ? finish.getAsString() : "stop";
        boolean refusal = messageObject.has("refusal") && !messageObject.get("refusal").isJsonNull();
        return new Choice(text, reason, refusal, messageRole);
    }

    private static ProviderFailure standardFailure(int status, boolean customAuthentication, Optional<JsonObject> body, boolean unused) {
        if (status == 401 || (customAuthentication && status == 403)) return new ProviderFailure(ProviderFailureKind.HTTP_AUTHENTICATION);
        if (status == 429) return new ProviderFailure(ProviderFailureKind.HTTP_RATE_LIMITED);
        if (status >= 500 && status <= 599) return new ProviderFailure(ProviderFailureKind.HTTP_SERVER_ERROR);
        return new ProviderFailure(ProviderFailureKind.HTTP_NON_RETRYABLE);
    }

    private static ProviderFailure openRouterFailure(int status, HttpHeaders headers, Optional<JsonObject> body) {
        if (body.isPresent() && body.get().has("error") && body.get().get("error").isJsonObject()) {
            LanguageModelResult result = failureFromOpenRouterError(body.get().getAsJsonObject("error"), headers);
            if (result instanceof ProviderFailure failure) return failure;
            return new ProviderFailure(ProviderFailureKind.HTTP_NON_RETRYABLE);
        }
        if (status == 401) return new ProviderFailure(ProviderFailureKind.HTTP_AUTHENTICATION);
        if (status == 408) return new ProviderFailure(ProviderFailureKind.TIMEOUT);
        if (status == 429) return rateLimited(headers);
        if (status >= 500 && status <= 599) return new ProviderFailure(ProviderFailureKind.HTTP_SERVER_ERROR, retryAfter(headers));
        return new ProviderFailure(ProviderFailureKind.HTTP_NON_RETRYABLE);
    }

    private static LanguageModelResult failureFromOpenRouterError(JsonObject error, HttpHeaders headers) {
        int status = error.has("code") && error.get("code").isJsonPrimitive() ? safeInt(error.get("code")) : 0;
        String errorType = "";
        if (error.has("metadata") && error.get("metadata").isJsonObject()) {
            JsonElement value = error.getAsJsonObject("metadata").get("error_type");
            if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) errorType = value.getAsString();
        }
        if ("content_policy_violation".equals(errorType)) return new ProviderRefusal(RefusalCode.PROVIDER_REFUSED);
        if (status == 401 || "authentication".equals(errorType)) return new ProviderFailure(ProviderFailureKind.HTTP_AUTHENTICATION);
        if (status == 408 || "timeout".equals(errorType)) return new ProviderFailure(ProviderFailureKind.TIMEOUT);
        if (status == 429 || "rate_limit_exceeded".equals(errorType)) return rateLimited(headers);
        if (status == 400 || "invalid_request".equals(errorType) || "context_length_exceeded".equals(errorType)) {
            return new ProviderFailure(ProviderFailureKind.INCOMPATIBLE_MODEL_OR_PARAMETER);
        }
        if (status >= 500 || "provider_unavailable".equals(errorType) || "provider_overloaded".equals(errorType) || "server".equals(errorType)) {
            return new ProviderFailure(ProviderFailureKind.HTTP_SERVER_ERROR, retryAfter(headers));
        }
        return new ProviderFailure(ProviderFailureKind.HTTP_NON_RETRYABLE);
    }

    private static int safeInt(JsonElement value) {
        try { return value.getAsInt(); } catch (RuntimeException ignored) { return 0; }
    }

    private static ProviderFailure rateLimited(HttpHeaders headers) {
        return new ProviderFailure(ProviderFailureKind.HTTP_RATE_LIMITED, retryAfter(headers));
    }

    private static Optional<Duration> retryAfter(HttpHeaders headers) {
        return headers.firstValue("Retry-After").flatMap(value -> {
            try {
                long seconds = Long.parseLong(value.trim());
                return seconds > 0 && seconds <= 86_400 ? Optional.of(Duration.ofSeconds(seconds)) : Optional.empty();
            } catch (RuntimeException ignored) { return Optional.empty(); }
        });
    }

    private record Choice(String content, String finishReason, boolean refusal, String role) { }
    @FunctionalInterface interface RequestMapper { String serialize(ProviderRequest request, ChatCompletionsPromptRenderer renderer); }
    @FunctionalInterface interface ResponseDecoder { LanguageModelResult decode(JsonObject body); }
    @FunctionalInterface interface HttpFailureClassifier { ProviderFailure classify(int status, HttpHeaders headers, Optional<JsonObject> body); }
    @FunctionalInterface interface ModelValidator { Optional<String> validate(String model, GenerationParameters generation); }
}
