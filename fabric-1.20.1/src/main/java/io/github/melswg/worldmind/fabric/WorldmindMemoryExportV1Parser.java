package io.github.melswg.worldmind.fabric;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.melswg.worldmind.core.administration.MemoryRecordType;

/** Structural parser contract for portable v1. Importing data is deliberately not implemented. */
final class WorldmindMemoryExportV1Parser {
    private WorldmindMemoryExportV1Parser() { }

    static int validate(String json) {
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid Worldmind export.");
        }
        if (!WorldmindMemoryExportPublisher.FORMAT_NAME.equals(string(root, "formatName"))
            || number(root, "formatVersion") != WorldmindMemoryExportPublisher.FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported Worldmind export version.");
        }
        JsonObject metadata = object(root, "metadata");
        string(metadata, "exportId"); string(metadata, "createdAt"); string(metadata, "worldId"); number(metadata, "storageSchemaVersion");
        JsonObject scope = object(metadata, "scope"); string(scope, "type"); nullableString(scope, "playerUuid");
        JsonArray records = array(root, "records");
        for (JsonElement element : records) validateRecord(element.getAsJsonObject());
        JsonObject counts = object(root, "recordCounts");
        for (MemoryRecordType type : MemoryRecordType.values()) number(counts, type.name());
        return records.size();
    }

    private static void validateRecord(JsonObject record) {
        string(record, "id"); MemoryRecordType.valueOf(string(record, "recordType"));
        range(object(record, "sequence"));
        JsonObject scope = object(record, "scope"); string(scope, "type"); nullableString(scope, "playerUuid");
        string(record, "visibility"); string(record, "sourceType");
        JsonObject timestamps = object(record, "timestamps"); string(timestamps, "source"); string(timestamps, "recorded");
        nullableNumber(record, "confidence"); nullableNumber(record, "importance");
        JsonObject state = object(record, "state"); nullableString(state, "memoryState"); nullableNumber(state, "version");
        nullableBoolean(state, "latest"); nullableString(state, "supersededBy"); nullableString(state, "confirmationAuthority");
        nullableString(state, "confirmedAt"); nullableString(record, "relationshipSubjectPlayerUuid");
        JsonObject provenance = object(record, "provenance"); range(object(provenance, "rawRange")); array(provenance, "sourceBatchIds");
        JsonObject payload = object(record, "payload"); string(payload, "content"); nullableString(payload, "actorPlayerUuid");
        array(payload, "membershipSequences");
    }

    private static void range(JsonObject range) { number(range, "first"); number(range, "last"); }
    private static JsonObject object(JsonObject object, String name) { return object.getAsJsonObject(name); }
    private static JsonArray array(JsonObject object, String name) { return object.getAsJsonArray(name); }
    private static String string(JsonObject object, String name) { return object.get(name).getAsString(); }
    private static int number(JsonObject object, String name) { return object.get(name).getAsInt(); }
    private static void nullableString(JsonObject object, String name) { if (!object.get(name).isJsonNull()) object.get(name).getAsString(); }
    private static void nullableNumber(JsonObject object, String name) { if (!object.get(name).isJsonNull()) object.get(name).getAsNumber(); }
    private static void nullableBoolean(JsonObject object, String name) { if (!object.get(name).isJsonNull()) object.get(name).getAsBoolean(); }
}
