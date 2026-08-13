package io.github.melswg.worldmind.fabric;

import com.google.gson.stream.JsonWriter;
import io.github.melswg.worldmind.core.administration.AdministrationResultCode;
import io.github.melswg.worldmind.core.administration.MemoryExportPage;
import io.github.melswg.worldmind.core.administration.MemoryExportQuery;
import io.github.melswg.worldmind.core.administration.MemoryExportRecord;
import io.github.melswg.worldmind.core.administration.MemoryExportRepository;
import io.github.melswg.worldmind.core.administration.MemoryExportResult;
import io.github.melswg.worldmind.core.administration.MemoryInspectionCursor;
import io.github.melswg.worldmind.core.administration.MemoryInspectionScope;
import io.github.melswg.worldmind.core.administration.MemoryRecordType;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fabric filesystem boundary for a deterministic, UTF-8 v1 artifact. The
 * journal supplies bounded pages; this executor alone performs file I/O.
 */
final class WorldmindMemoryExportPublisher {
    static final String FORMAT_NAME = "worldmind-memory-export";
    static final int FORMAT_VERSION = 1;
    private final ExecutorService executor;
    private final Clock clock;
    private final AtomicReference<Operation> active = new AtomicReference<>();

    WorldmindMemoryExportPublisher(ExecutorService executor, Clock clock) {
        this.executor = executor;
        this.clock = clock;
    }

    CompletionStage<MemoryExportResult> export(
        MemoryExportRepository repository,
        Path saveRoot,
        String worldId,
        MemoryInspectionScope scope
    ) {
        Operation operation = new Operation();
        if (!active.compareAndSet(null, operation)) {
            return CompletableFuture.completedFuture(MemoryExportResult.of(AdministrationResultCode.EXPORT_IN_PROGRESS));
        }
        CompletableFuture<MemoryExportResult> result = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                result.complete(write(repository, saveRoot, worldId, scope, operation));
            } catch (RuntimeException failure) {
                result.complete(MemoryExportResult.of(AdministrationResultCode.IO_FAILURE));
            } finally {
                active.compareAndSet(operation, null);
            }
        });
        return result;
    }

    void cancelActive() {
        Operation operation = active.get();
        if (operation != null) operation.cancelled.set(true);
    }

    private MemoryExportResult write(
        MemoryExportRepository repository,
        Path saveRoot,
        String worldId,
        MemoryInspectionScope scope,
        Operation operation
    ) {
        Path temporary = null;
        boolean published = false;
        try {
            if (operation.cancelled.get()) return MemoryExportResult.of(AdministrationResultCode.CANCELLED);
            Path root = saveRoot.toRealPath();
            Path directory = saveRoot.resolve("worldmind").resolve("exports");
            Files.createDirectories(directory);
            Path realDirectory = directory.toRealPath();
            if (!realDirectory.startsWith(root)) return MemoryExportResult.of(AdministrationResultCode.UNSAFE_EXPORT_PATH);
            Artifact artifact = nextArtifact(realDirectory, scope);
            temporary = artifact.temporary();
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                 JsonWriter json = new JsonWriter(new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(channel), StandardCharsets.UTF_8)))) {
                json.setSerializeNulls(true);
                writeDocument(json, repository, worldId, scope, operation);
                json.flush();
                channel.force(true);
            }
            if (operation.cancelled.get()) return MemoryExportResult.of(AdministrationResultCode.CANCELLED);
            try {
                Files.move(temporary, artifact.target(), StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                return MemoryExportResult.of(AdministrationResultCode.IO_FAILURE);
            }
            published = true;
            return MemoryExportResult.completed("worldmind/exports/" + artifact.target().getFileName());
        } catch (java.util.concurrent.CancellationException cancelled) {
            return MemoryExportResult.of(AdministrationResultCode.CANCELLED);
        } catch (IOException | java.util.concurrent.CompletionException failure) {
            return MemoryExportResult.of(AdministrationResultCode.IO_FAILURE);
        } finally {
            if (!published && temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            }
        }
    }

    private Artifact nextArtifact(Path directory, MemoryInspectionScope scope) throws IOException {
        for (int attempts = 0; attempts < 16; attempts++) {
            UUID id = UUID.randomUUID();
            String timestamp = DateTimeFormatter.ISO_INSTANT.format(clock.instant()).replace(":", "").replace("-", "");
            String name = scope.kind() == MemoryInspectionScope.Kind.WORLD
                ? "worldmind-memory-v1-world-" + timestamp + "-" + id + ".json"
                : "worldmind-memory-v1-player-" + scope.playerId().orElseThrow() + "-" + timestamp + "-" + id + ".json";
            Path target = directory.resolve(name);
            if (!Files.exists(target)) return new Artifact(target, directory.resolve("." + name + ".tmp-" + id));
        }
        throw new IOException("Could not reserve export artifact.");
    }

    private void writeDocument(
        JsonWriter json,
        MemoryExportRepository repository,
        String worldId,
        MemoryInspectionScope scope,
        Operation operation
    ) throws IOException {
        EnumMap<MemoryRecordType, Integer> counts = new EnumMap<>(MemoryRecordType.class);
        json.beginObject();
        json.name("formatName").value(FORMAT_NAME);
        json.name("formatVersion").value(FORMAT_VERSION);
        json.name("metadata"); writeMetadata(json, worldId, scope);
        json.name("records").beginArray();
        for (MemoryRecordType type : MemoryRecordType.values()) {
            Optional<MemoryInspectionCursor> cursor = Optional.empty();
            do {
                checkCancelled(operation);
                MemoryExportPage page = repository.exportPage(new MemoryExportQuery(scope, type, cursor)).toCompletableFuture().join();
                for (MemoryExportRecord record : page.records()) {
                    checkCancelled(operation);
                    writeRecord(json, record);
                    counts.merge(type, 1, Integer::sum);
                }
                cursor = page.next();
            } while (cursor.isPresent());
        }
        json.endArray();
        json.name("recordCounts").beginObject();
        for (MemoryRecordType type : MemoryRecordType.values()) json.name(type.name()).value(counts.getOrDefault(type, 0));
        json.endObject();
        json.endObject();
    }

    private void writeMetadata(JsonWriter json, String worldId, MemoryInspectionScope scope) throws IOException {
        json.beginObject();
        json.name("exportId").value(UUID.randomUUID().toString());
        json.name("createdAt").value(DateTimeFormatter.ISO_INSTANT.format(clock.instant()));
        json.name("worldId").value(worldId);
        json.name("storageSchemaVersion").value(1);
        json.name("scope").beginObject();
        json.name("type").value(scope.kind().name());
        json.name("playerUuid"); nullable(json, scope.playerId().map(UUID::toString));
        json.endObject();
        json.endObject();
    }

    private void writeRecord(JsonWriter json, MemoryExportRecord record) throws IOException {
        json.beginObject();
        json.name("id").value(record.stableIdentity());
        json.name("recordType").value(record.recordType().name());
        json.name("sequence").beginObject().name("first").value(record.firstSequence()).name("last").value(record.lastSequence()).endObject();
        json.name("scope").beginObject().name("type").value(record.scope().kind().name()).name("playerUuid");
        nullable(json, record.scope().playerId().map(UUID::toString)); json.endObject();
        json.name("visibility").value(record.visibility());
        json.name("sourceType").value(record.sourceType());
        json.name("timestamps").beginObject().name("source").value(record.sourceTimestamp().toString())
            .name("recorded").value(record.recordedAt().toString()).endObject();
        json.name("confidence"); nullable(json, record.confidence());
        json.name("importance"); nullable(json, record.importance());
        json.name("state").beginObject();
        json.name("memoryState"); nullable(json, record.state());
        json.name("version"); nullable(json, record.version());
        json.name("latest"); nullable(json, record.latest());
        json.name("supersededBy"); nullable(json, record.supersededBy());
        json.name("confirmationAuthority"); nullable(json, record.confirmationAuthority());
        json.name("confirmedAt"); nullable(json, record.confirmedAt().map(Instant::toString));
        json.endObject();
        json.name("relationshipSubjectPlayerUuid"); nullable(json, record.relationshipSubjectPlayerId().map(UUID::toString));
        json.name("provenance").beginObject();
        json.name("rawRange").beginObject().name("first").value(record.provenance().firstSequence())
            .name("last").value(record.provenance().lastSequence()).endObject();
        json.name("sourceBatchIds").beginArray(); for (String id : record.provenance().sourceBatchIds()) json.value(id); json.endArray();
        json.endObject();
        json.name("payload").beginObject();
        json.name("content").value(record.content());
        json.name("actorPlayerUuid"); nullable(json, record.actorPlayerId().map(UUID::toString));
        json.name("membershipSequences").beginArray(); for (Long sequence : record.membershipSequences()) json.value(sequence); json.endArray();
        json.endObject();
        json.endObject();
    }

    private static void nullable(JsonWriter json, Optional<?> value) throws IOException {
        if (value.isEmpty()) json.nullValue();
        else if (value.orElseThrow() instanceof Number number) json.value(number);
        else if (value.orElseThrow() instanceof Boolean bool) json.value(bool);
        else json.value(value.orElseThrow().toString());
    }

    private static void checkCancelled(Operation operation) {
        if (operation.cancelled.get()) throw new java.util.concurrent.CancellationException();
    }

    private record Artifact(Path target, Path temporary) { }
    private static final class Operation { private final AtomicBoolean cancelled = new AtomicBoolean(); }
}
