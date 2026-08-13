package io.github.melswg.worldmind.fabric.configuration;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonParseException;
import io.github.melswg.worldmind.core.configuration.ConfigurationDiagnostic;
import io.github.melswg.worldmind.core.configuration.ChatNameColor;
import io.github.melswg.worldmind.core.configuration.ChatBatchingConfiguration;
import io.github.melswg.worldmind.core.configuration.DisabledWorldmindIntegration;
import io.github.melswg.worldmind.core.configuration.EnabledWorldmindIntegration;
import io.github.melswg.worldmind.core.configuration.ExternalSecretReference;
import io.github.melswg.worldmind.core.configuration.GenerationParameters;
import io.github.melswg.worldmind.core.configuration.IntegrationDisableReason;
import io.github.melswg.worldmind.core.configuration.LoreMaterial;
import io.github.melswg.worldmind.core.configuration.ProviderConfiguration;
import io.github.melswg.worldmind.core.configuration.ProviderEndpoint;
import io.github.melswg.worldmind.core.configuration.RequestQueueConfiguration;
import io.github.melswg.worldmind.core.configuration.ResponseLengthLimit;
import io.github.melswg.worldmind.core.configuration.SecretAvailability;
import io.github.melswg.worldmind.core.configuration.SecretResolver;
import io.github.melswg.worldmind.core.configuration.ValidatedWorldmindConfiguration;
import io.github.melswg.worldmind.core.configuration.WorldmindGlobalConfiguration;
import io.github.melswg.worldmind.core.configuration.WorldmindIntegrationState;
import io.github.melswg.worldmind.core.configuration.WorldmindProfile;
import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads the strict, read-only v1 filesystem format at
 * {@code config/worldmind}. JSON is confined to this Fabric adapter; core sees
 * only validated, JSON-library-independent values.
 */
public final class WorldmindStartupConfigurationLoader {
    private static final String GLOBAL_FILE_NAME = "worldmind.json";
    private static final String PROFILE_FILE_NAME = "profile.json";
    private static final Pattern PROFILE_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
    private static final Pattern ENV_SECRET_REFERENCE = Pattern.compile("env:[A-Za-z_][A-Za-z0-9_]*");
    private static final String CUSTOM_OPENAI_COMPATIBLE = "custom-openai-compatible";
    private static final Set<String> GLOBAL_FIELDS = Set.of(
        "schemaVersion",
        "enabled",
        "activeProfile",
        "provider",
        "chatBatching",
        "requestQueue"
    );
    private static final Set<String> CHAT_BATCHING_FIELDS = Set.of(
        "maxMessages",
        "maxWaitMillis",
        "maxEstimatedInputCharacters"
    );
    private static final Set<String> REQUEST_QUEUE_FIELDS = Set.of("capacity", "maxConcurrency");
    private static final Set<String> PROVIDER_FIELDS = Set.of("id", "endpoint", "model", "generation", "secretReference");
    private static final Set<String> GENERATION_FIELDS = Set.of("temperature", "topP", "maxOutputTokens");
    private static final Set<String> PROFILE_FIELDS = Set.of(
        "schemaVersion",
        "characterName",
        "personaFile",
        "administratorRulesFile",
        "loreFiles",
        "responseStyle",
        "responseLengthLimit",
        "chatNameColor"
    );

    private final Path configurationDirectory;
    private final SecretResolver secretResolver;

    public WorldmindStartupConfigurationLoader(Path configurationDirectory, SecretResolver secretResolver) {
        this.configurationDirectory = Objects.requireNonNull(configurationDirectory, "configurationDirectory");
        this.secretResolver = Objects.requireNonNull(secretResolver, "secretResolver");
    }

    /**
     * Validates all configured v1 inputs before reporting the integration as
     * enabled. This method never creates, migrates, or rewrites configuration.
     */
    public WorldmindIntegrationState load() {
        List<ConfigurationDiagnostic> diagnostics = new ArrayList<>();
        ParsedGlobal global = parseGlobal(configurationDirectory.resolve(GLOBAL_FILE_NAME), diagnostics);
        ParsedProfile profile = null;

        if (global != null) {
            Path profileDirectory = profileDirectory(global.activeProfile(), diagnostics);
            if (profileDirectory != null) {
                profile = parseProfile(profileDirectory, diagnostics);
            }
        }

        if (!diagnostics.isEmpty()) {
            return disabled(IntegrationDisableReason.INVALID_CONFIGURATION, diagnostics);
        }

        WorldmindGlobalConfiguration globalConfiguration = new WorldmindGlobalConfiguration(
            global.schemaVersion(),
            global.enabled(),
            global.activeProfile(),
            new ProviderConfiguration(
                global.providerId(),
                global.endpoint(),
                global.model(),
                new GenerationParameters(global.temperature(), global.topP(), global.maxOutputTokens()),
                new ExternalSecretReference(global.secretReference())
            ),
            global.chatBatching(),
            global.requestQueue()
        );
        WorldmindProfile worldmindProfile = new WorldmindProfile(
            profile.schemaVersion(),
            profile.characterName(),
            profile.persona(),
            profile.administratorRules(),
            profile.loreMaterials(),
            profile.responseStyle(),
            new ResponseLengthLimit(profile.responseLengthLimit()),
            profile.chatNameColor()
        );
        ValidatedWorldmindConfiguration validated = new ValidatedWorldmindConfiguration(globalConfiguration, worldmindProfile);

        if (!globalConfiguration.enabled()) {
            return disabled(
                IntegrationDisableReason.DISABLED_BY_OPERATOR,
                List.of(new ConfigurationDiagnostic("global.enabled", "Worldmind is disabled by configuration."))
            );
        }

        SecretAvailability availability;
        try {
            availability = secretResolver.check(globalConfiguration.provider().secretReference());
        } catch (RuntimeException failure) {
            availability = SecretAvailability.UNREADABLE;
        }
        if (availability == null || availability == SecretAvailability.UNREADABLE) {
            return disabled(
                IntegrationDisableReason.SECRET_UNAVAILABLE,
                List.of(new ConfigurationDiagnostic(
                    "global.provider.secretReference",
                    "Secret material is unavailable or unreadable."
                ))
            );
        }
        if (availability == SecretAvailability.MISSING) {
            return disabled(
                IntegrationDisableReason.SECRET_UNAVAILABLE,
                List.of(new ConfigurationDiagnostic("global.provider.secretReference", "Secret material is missing."))
            );
        }

        return new EnabledWorldmindIntegration(validated);
    }

    private ParsedGlobal parseGlobal(Path globalFile, List<ConfigurationDiagnostic> diagnostics) {
        JsonObject global = readJsonObject(globalFile, "global", diagnostics);
        if (global == null) {
            return null;
        }
        rejectUnknownFields(global, "global", GLOBAL_FIELDS, diagnostics);

        Integer schemaVersion = requiredInteger(global, "schemaVersion", "global", diagnostics);
        if (schemaVersion != null && schemaVersion != WorldmindGlobalConfiguration.V1_SCHEMA_VERSION) {
            diagnostic(
                diagnostics,
                "global.schemaVersion",
                "must be exactly supported schema version " + WorldmindGlobalConfiguration.V1_SCHEMA_VERSION
                    + "; found " + schemaVersion + "."
            );
        }
        Boolean enabled = requiredBoolean(global, "enabled", "global", diagnostics);
        String activeProfile = requiredString(global, "activeProfile", "global", diagnostics);
        JsonObject chatBatching = requiredObject(global, "chatBatching", "global", diagnostics);
        ChatBatchingConfiguration batchingConfiguration = parseChatBatching(chatBatching, diagnostics);
        JsonObject requestQueue = requiredObject(global, "requestQueue", "global", diagnostics);
        RequestQueueConfiguration requestQueueConfiguration = parseRequestQueue(requestQueue, diagnostics);

        JsonObject provider = requiredObject(global, "provider", "global", diagnostics);
        if (provider == null) {
            return null;
        }
        rejectUnknownFields(provider, "global.provider", PROVIDER_FIELDS, diagnostics);
        String providerId = requiredString(provider, "id", "global.provider", diagnostics);
        String endpointValue = requiredString(provider, "endpoint", "global.provider", diagnostics);
        String model = requiredString(provider, "model", "global.provider", diagnostics);
        String secretReference = requiredString(provider, "secretReference", "global.provider", diagnostics);
        ProviderEndpoint endpoint = endpointValue == null ? null : parseProviderEndpoint(endpointValue, diagnostics);
        validateProviderId(providerId, diagnostics);
        validateSecretReference(secretReference, diagnostics);

        JsonObject generation = requiredObject(provider, "generation", "global.provider", diagnostics);
        if (generation == null) {
            return null;
        }
        rejectUnknownFields(generation, "global.provider.generation", GENERATION_FIELDS, diagnostics);
        Optional<Double> temperature = optionalDouble(generation, "temperature", "global.provider.generation", diagnostics);
        Optional<Double> topP = optionalDouble(generation, "topP", "global.provider.generation", diagnostics);
        Optional<Integer> maxOutputTokens = optionalInteger(
            generation,
            "maxOutputTokens",
            "global.provider.generation",
            diagnostics
        );
        validateGenerationParameters(temperature, topP, maxOutputTokens, diagnostics);

        if (schemaVersion == null || enabled == null || activeProfile == null || batchingConfiguration == null || requestQueueConfiguration == null
            || providerId == null || endpoint == null || model == null || secretReference == null) {
            return null;
        }
        return new ParsedGlobal(
            schemaVersion,
            enabled,
            activeProfile,
            providerId,
            endpoint,
            model,
            temperature,
            topP,
            maxOutputTokens,
            secretReference,
            batchingConfiguration,
            requestQueueConfiguration
        );
    }

    private RequestQueueConfiguration parseRequestQueue(JsonObject requestQueue, List<ConfigurationDiagnostic> diagnostics) {
        if (requestQueue == null) return null;
        rejectUnknownFields(requestQueue, "global.requestQueue", REQUEST_QUEUE_FIELDS, diagnostics);
        Integer capacity = requiredInteger(requestQueue, "capacity", "global.requestQueue", diagnostics);
        Integer maxConcurrency = requiredInteger(requestQueue, "maxConcurrency", "global.requestQueue", diagnostics);
        validatePositiveBatchingValue(capacity, "global.requestQueue.capacity", diagnostics);
        validatePositiveBatchingValue(maxConcurrency, "global.requestQueue.maxConcurrency", diagnostics);
        if (capacity == null || maxConcurrency == null || capacity <= 0 || maxConcurrency <= 0) return null;
        return new RequestQueueConfiguration(capacity, maxConcurrency);
    }

    private ChatBatchingConfiguration parseChatBatching(
        JsonObject chatBatching,
        List<ConfigurationDiagnostic> diagnostics
    ) {
        if (chatBatching == null) {
            return null;
        }
        rejectUnknownFields(chatBatching, "global.chatBatching", CHAT_BATCHING_FIELDS, diagnostics);
        Integer maxMessages = requiredInteger(chatBatching, "maxMessages", "global.chatBatching", diagnostics);
        Integer maxWaitMillis = requiredInteger(chatBatching, "maxWaitMillis", "global.chatBatching", diagnostics);
        Integer maxEstimatedInputCharacters = requiredInteger(
            chatBatching,
            "maxEstimatedInputCharacters",
            "global.chatBatching",
            diagnostics
        );
        validatePositiveBatchingValue(maxMessages, "global.chatBatching.maxMessages", diagnostics);
        validatePositiveBatchingValue(maxWaitMillis, "global.chatBatching.maxWaitMillis", diagnostics);
        validatePositiveBatchingValue(
            maxEstimatedInputCharacters,
            "global.chatBatching.maxEstimatedInputCharacters",
            diagnostics
        );
        if (maxMessages == null || maxWaitMillis == null || maxEstimatedInputCharacters == null
            || maxMessages <= 0 || maxWaitMillis <= 0 || maxEstimatedInputCharacters <= 0) {
            return null;
        }
        return new ChatBatchingConfiguration(maxMessages, maxWaitMillis, maxEstimatedInputCharacters);
    }

    private void validatePositiveBatchingValue(
        Integer value,
        String field,
        List<ConfigurationDiagnostic> diagnostics
    ) {
        if (value != null && value <= 0) {
            diagnostic(diagnostics, field, "must be a positive, safely representable integer.");
        }
    }

    private ProviderEndpoint parseProviderEndpoint(String endpointValue, List<ConfigurationDiagnostic> diagnostics) {
        URI endpoint;
        try {
            endpoint = new URI(endpointValue);
        } catch (URISyntaxException failure) {
            diagnostic(diagnostics, "global.provider.endpoint", "must be a valid absolute HTTPS or loopback HTTP URI.");
            return null;
        }
        if (!endpoint.isAbsolute() || endpoint.getHost() == null) {
            diagnostic(diagnostics, "global.provider.endpoint", "must be an absolute URI with a host.");
            return null;
        }
        if (endpoint.getRawUserInfo() != null) {
            diagnostic(diagnostics, "global.provider.endpoint", "must not contain user-info.");
            return null;
        }
        if (endpoint.getRawFragment() != null) {
            diagnostic(diagnostics, "global.provider.endpoint", "must not contain a fragment.");
            return null;
        }

        String scheme = endpoint.getScheme();
        if ("https".equalsIgnoreCase(scheme)) {
            return new ProviderEndpoint(endpoint);
        }
        if ("http".equalsIgnoreCase(scheme) && isSyntacticLoopbackHost(endpoint.getHost())) {
            return new ProviderEndpoint(endpoint);
        }
        diagnostic(
            diagnostics,
            "global.provider.endpoint",
            "must use HTTPS, or HTTP only with localhost, IPv4 loopback, or ::1."
        );
        return null;
    }

    private void validateProviderId(String providerId, List<ConfigurationDiagnostic> diagnostics) {
        if (providerId != null && !CUSTOM_OPENAI_COMPATIBLE.equals(providerId)) {
            diagnostic(
                diagnostics,
                "global.provider.id",
                "must be \"" + CUSTOM_OPENAI_COMPATIBLE + "\" in v1."
            );
        }
    }

    private void validateSecretReference(String secretReference, List<ConfigurationDiagnostic> diagnostics) {
        if (secretReference != null && !ENV_SECRET_REFERENCE.matcher(secretReference).matches()) {
            diagnostic(
                diagnostics,
                "global.provider.secretReference",
                "must use the env:NAME reference format."
            );
        }
    }

    private boolean isSyntacticLoopbackHost(String host) {
        String normalized = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        if ("localhost".equalsIgnoreCase(normalized) || "::1".equals(normalized)) {
            return true;
        }
        String[] octets = normalized.split("\\.", -1);
        if (octets.length != 4 || !"127".equals(octets[0])) {
            return false;
        }
        for (String octet : octets) {
            try {
                int value = Integer.parseInt(octet);
                if (value < 0 || value > 255) {
                    return false;
                }
            } catch (NumberFormatException failure) {
                return false;
            }
        }
        return true;
    }

    private ParsedProfile parseProfile(Path profileDirectory, List<ConfigurationDiagnostic> diagnostics) {
        JsonObject profile = readJsonObject(profileDirectory.resolve(PROFILE_FILE_NAME), "profile", diagnostics);
        if (profile == null) {
            return null;
        }
        rejectUnknownFields(profile, "profile", PROFILE_FIELDS, diagnostics);

        Integer schemaVersion = requiredInteger(profile, "schemaVersion", "profile", diagnostics);
        if (schemaVersion != null && schemaVersion != WorldmindProfile.V1_SCHEMA_VERSION) {
            diagnostic(
                diagnostics,
                "profile.schemaVersion",
                "must be exactly supported schema version " + WorldmindProfile.V1_SCHEMA_VERSION
                    + "; found " + schemaVersion + "."
            );
        }
        String characterName = requiredString(profile, "characterName", "profile", diagnostics);
        String personaFile = requiredString(profile, "personaFile", "profile", diagnostics);
        String administratorRulesFile = requiredString(profile, "administratorRulesFile", "profile", diagnostics);
        String responseStyle = requiredString(profile, "responseStyle", "profile", diagnostics);
        Integer responseLengthLimit = requiredInteger(profile, "responseLengthLimit", "profile", diagnostics);
        ChatNameColor chatNameColor = optionalChatNameColor(profile, diagnostics);
        if (responseLengthLimit != null && responseLengthLimit <= 0) {
            diagnostic(diagnostics, "profile.responseLengthLimit", "must be a positive number of characters.");
        }

        String persona = personaFile == null ? null : readProfileText(
            profileDirectory,
            personaFile,
            "profile.personaFile",
            diagnostics
        );
        String administratorRules = administratorRulesFile == null ? null : readProfileText(
            profileDirectory,
            administratorRulesFile,
            "profile.administratorRulesFile",
            diagnostics
        );
        List<LoreMaterial> loreMaterials = readLoreMaterials(profileDirectory, profile, diagnostics);

        if (schemaVersion == null || characterName == null || persona == null || administratorRules == null
            || responseStyle == null || responseLengthLimit == null || responseLengthLimit <= 0
            || loreMaterials == null || chatNameColor == null) {
            return null;
        }
        return new ParsedProfile(
            schemaVersion,
            characterName,
            persona,
            administratorRules,
            loreMaterials,
            responseStyle,
            responseLengthLimit,
            chatNameColor
        );
    }

    private ChatNameColor optionalChatNameColor(JsonObject profile, List<ConfigurationDiagnostic> diagnostics) {
        if (!profile.has("chatNameColor")) {
            return ChatNameColor.LIGHT_PURPLE;
        }
        JsonElement value = profile.get("chatNameColor");
        if (!isString(value)) {
            diagnostic(diagnostics, "profile.chatNameColor", "must be a supported case-sensitive vanilla color name.");
            return null;
        }
        return ChatNameColor.fromProfileValue(value.getAsString()).orElseGet(() -> {
            diagnostic(diagnostics, "profile.chatNameColor", "must be a supported case-sensitive vanilla color name.");
            return null;
        });
    }

    private List<LoreMaterial> readLoreMaterials(
        Path profileDirectory,
        JsonObject profile,
        List<ConfigurationDiagnostic> diagnostics
    ) {
        JsonElement element = profile.get("loreFiles");
        if (element == null) {
            diagnostic(diagnostics, "profile.loreFiles", "is required.");
            return null;
        }
        if (!element.isJsonArray()) {
            diagnostic(diagnostics, "profile.loreFiles", "must be an array of profile-relative file paths.");
            return null;
        }
        JsonArray loreFiles = element.getAsJsonArray();
        if (loreFiles.size() == 0) {
            diagnostic(diagnostics, "profile.loreFiles", "must contain at least one lore material.");
            return null;
        }

        List<LoreMaterial> loreMaterials = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (int index = 0; index < loreFiles.size(); index++) {
            String field = "profile.loreFiles[" + index + "]";
            JsonElement loreFile = loreFiles.get(index);
            if (!isString(loreFile)) {
                diagnostic(diagnostics, field, "must be a non-blank profile-relative file path.");
                continue;
            }
            String fileName = loreFile.getAsString();
            if (fileName.isBlank()) {
                diagnostic(diagnostics, field, "must be a non-blank profile-relative file path.");
                continue;
            }
            if (!names.add(fileName)) {
                diagnostic(diagnostics, field, "must not repeat a lore material path.");
                continue;
            }
            String content = readProfileText(profileDirectory, fileName, field, diagnostics);
            if (content != null) {
                loreMaterials.add(new LoreMaterial(fileName, content));
            }
        }
        return loreMaterials.size() == loreFiles.size() ? List.copyOf(loreMaterials) : null;
    }

    private Path profileDirectory(String profileId, List<ConfigurationDiagnostic> diagnostics) {
        if (!PROFILE_ID.matcher(profileId).matches()) {
            diagnostic(
                diagnostics,
                "global.activeProfile",
                "must contain lowercase letters, digits, and single hyphens only."
            );
            return null;
        }
        return configurationDirectory.resolve("profiles").resolve(profileId);
    }

    private String readProfileText(
        Path profileDirectory,
        String relativeFileName,
        String field,
        List<ConfigurationDiagnostic> diagnostics
    ) {
        Path requested;
        try {
            requested = Path.of(relativeFileName);
        } catch (InvalidPathException failure) {
            diagnostic(diagnostics, field, "must name a relative file contained by the active profile.");
            return null;
        }
        if (requested.isAbsolute()) {
            diagnostic(diagnostics, field, "must name a relative file contained by the active profile.");
            return null;
        }

        try {
            Path realProfileDirectory = profileDirectory.toRealPath();
            Path realFile = profileDirectory.resolve(requested).normalize().toRealPath();
            if (!realFile.startsWith(realProfileDirectory)) {
                diagnostic(diagnostics, field, "must name a file contained by the active profile.");
                return null;
            }
            String content = Files.readString(realFile, StandardCharsets.UTF_8);
            if (content.isBlank()) {
                diagnostic(diagnostics, field, "must not be empty.");
                return null;
            }
            return content;
        } catch (IOException failure) {
            diagnostic(diagnostics, field, "cannot be read from the active profile.");
            return null;
        }
    }

    private JsonObject readJsonObject(Path file, String field, List<ConfigurationDiagnostic> diagnostics) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement element = JsonParser.parseReader(reader);
            if (!element.isJsonObject()) {
                diagnostic(diagnostics, field, "must contain a JSON object.");
                return null;
            }
            return element.getAsJsonObject();
        } catch (JsonParseException failure) {
            diagnostic(diagnostics, field, "must contain valid JSON.");
            return null;
        } catch (IOException failure) {
            diagnostic(diagnostics, field, "cannot be read.");
            return null;
        }
    }

    private void rejectUnknownFields(
        JsonObject object,
        String field,
        Set<String> supportedFields,
        List<ConfigurationDiagnostic> diagnostics
    ) {
        for (String fieldName : object.keySet()) {
            if (!supportedFields.contains(fieldName)) {
                diagnostic(
                    diagnostics,
                    field + "." + fieldName,
                    "is not supported by the strict v1 schema."
                );
            }
        }
    }

    private Integer requiredInteger(
        JsonObject object,
        String name,
        String parentField,
        List<ConfigurationDiagnostic> diagnostics
    ) {
        String field = parentField + "." + name;
        JsonElement element = object.get(name);
        if (element == null) {
            diagnostic(diagnostics, field, "is required.");
            return null;
        }
        if (!isNumber(element)) {
            diagnostic(diagnostics, field, "must be an integer.");
            return null;
        }
        try {
            return element.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException failure) {
            diagnostic(diagnostics, field, "must be an integer.");
            return null;
        }
    }

    private Optional<Integer> optionalInteger(
        JsonObject object,
        String name,
        String parentField,
        List<ConfigurationDiagnostic> diagnostics
    ) {
        if (!object.has(name)) {
            return Optional.empty();
        }
        Integer value = requiredInteger(object, name, parentField, diagnostics);
        return Optional.ofNullable(value);
    }

    private Optional<Double> optionalDouble(
        JsonObject object,
        String name,
        String parentField,
        List<ConfigurationDiagnostic> diagnostics
    ) {
        if (!object.has(name)) {
            return Optional.empty();
        }
        String field = parentField + "." + name;
        JsonElement element = object.get(name);
        if (!isNumber(element)) {
            diagnostic(diagnostics, field, "must be a finite number.");
            return Optional.empty();
        }
        BigDecimal decimal;
        try {
            decimal = element.getAsBigDecimal();
        } catch (NumberFormatException failure) {
            diagnostic(diagnostics, field, "must be a finite number.");
            return Optional.empty();
        }
        double value = decimal.doubleValue();
        if (!Double.isFinite(value)) {
            diagnostic(diagnostics, field, "must be a finite number.");
            return Optional.empty();
        }
        return Optional.of(value);
    }

    private Boolean requiredBoolean(
        JsonObject object,
        String name,
        String parentField,
        List<ConfigurationDiagnostic> diagnostics
    ) {
        String field = parentField + "." + name;
        JsonElement element = object.get(name);
        if (element == null) {
            diagnostic(diagnostics, field, "is required.");
            return null;
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean()) {
            return element.getAsBoolean();
        }
        diagnostic(diagnostics, field, "must be true or false.");
        return null;
    }

    private String requiredString(
        JsonObject object,
        String name,
        String parentField,
        List<ConfigurationDiagnostic> diagnostics
    ) {
        String field = parentField + "." + name;
        JsonElement element = object.get(name);
        if (element == null) {
            diagnostic(diagnostics, field, "is required.");
            return null;
        }
        if (!isString(element) || element.getAsString().isBlank()) {
            diagnostic(diagnostics, field, "must be a non-blank string.");
            return null;
        }
        return element.getAsString();
    }

    private JsonObject requiredObject(
        JsonObject object,
        String name,
        String parentField,
        List<ConfigurationDiagnostic> diagnostics
    ) {
        String field = parentField + "." + name;
        JsonElement element = object.get(name);
        if (element == null) {
            diagnostic(diagnostics, field, "is required.");
            return null;
        }
        if (!element.isJsonObject()) {
            diagnostic(diagnostics, field, "must be an object.");
            return null;
        }
        return element.getAsJsonObject();
    }

    private void validateGenerationParameters(
        Optional<Double> temperature,
        Optional<Double> topP,
        Optional<Integer> maxOutputTokens,
        List<ConfigurationDiagnostic> diagnostics
    ) {
        temperature.ifPresent(value -> {
            if (value < 0.0 || value > 2.0) {
                diagnostic(diagnostics, "global.provider.generation.temperature", "must be between 0.0 and 2.0.");
            }
        });
        topP.ifPresent(value -> {
            if (value <= 0.0 || value > 1.0) {
                diagnostic(diagnostics, "global.provider.generation.topP", "must be greater than 0.0 and at most 1.0.");
            }
        });
        maxOutputTokens.ifPresent(value -> {
            if (value <= 0) {
                diagnostic(diagnostics, "global.provider.generation.maxOutputTokens", "must be positive.");
            }
        });
        if (temperature.isPresent() && topP.isPresent()) {
            diagnostic(
                diagnostics,
                "global.provider.generation",
                "temperature and topP cannot both be configured in v1."
            );
        }
    }

    private boolean isString(JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString();
    }

    private boolean isNumber(JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber();
    }

    private DisabledWorldmindIntegration disabled(
        IntegrationDisableReason reason,
        List<ConfigurationDiagnostic> diagnostics
    ) {
        return new DisabledWorldmindIntegration(reason, diagnostics);
    }

    private void diagnostic(List<ConfigurationDiagnostic> diagnostics, String field, String reason) {
        diagnostics.add(new ConfigurationDiagnostic(field, reason));
    }

    private record ParsedGlobal(
        int schemaVersion,
        boolean enabled,
        String activeProfile,
        String providerId,
        ProviderEndpoint endpoint,
        String model,
        Optional<Double> temperature,
        Optional<Double> topP,
        Optional<Integer> maxOutputTokens,
        String secretReference,
        ChatBatchingConfiguration chatBatching,
        RequestQueueConfiguration requestQueue
    ) {
    }

    private record ParsedProfile(
        int schemaVersion,
        String characterName,
        String persona,
        String administratorRules,
        List<LoreMaterial> loreMaterials,
        String responseStyle,
        int responseLengthLimit,
        ChatNameColor chatNameColor
    ) {
    }
}
