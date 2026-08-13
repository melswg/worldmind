package io.github.melswg.worldmind.core.administration;

/** The fixed inspection/export vocabulary for schema-v1 Worldmind memory. */
public enum MemoryRecordType {
    OBSERVATION,
    BATCH,
    OUTCOME,
    REPLY,
    FACT,
    RELATIONSHIP,
    EVENT,
    CURRENT_SITUATION,
    SUMMARY;

    public static MemoryRecordType commandValue(String value) {
        return switch (value) {
            case "observation" -> OBSERVATION;
            case "batch" -> BATCH;
            case "outcome" -> OUTCOME;
            case "reply" -> REPLY;
            case "fact" -> FACT;
            case "relationship" -> RELATIONSHIP;
            case "event" -> EVENT;
            case "situation" -> CURRENT_SITUATION;
            case "summary" -> SUMMARY;
            default -> throw new IllegalArgumentException("Unsupported record type.");
        };
    }

    public String commandValue() {
        return switch (this) {
            case OBSERVATION -> "observation";
            case BATCH -> "batch";
            case OUTCOME -> "outcome";
            case REPLY -> "reply";
            case FACT -> "fact";
            case RELATIONSHIP -> "relationship";
            case EVENT -> "event";
            case CURRENT_SITUATION -> "situation";
            case SUMMARY -> "summary";
        };
    }
}
