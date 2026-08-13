package io.github.melswg.worldmind.storage.sqlite;

import io.github.melswg.worldmind.core.conversation.AddressingSignal;
import io.github.melswg.worldmind.core.conversation.CapturedPublicChatMessage;
import io.github.melswg.worldmind.core.conversation.ChatBatchSealReason;
import io.github.melswg.worldmind.core.conversation.RefusalCode;
import io.github.melswg.worldmind.core.conversation.ServerRequester;
import io.github.melswg.worldmind.core.conversation.SealedChatBatch;
import io.github.melswg.worldmind.core.conversation.WorldIdentity;
import io.github.melswg.worldmind.core.journal.DialogueJournal;
import io.github.melswg.worldmind.core.journal.DialogueJournalSnapshot;
import io.github.melswg.worldmind.core.journal.JournalBatchOutcome;
import io.github.melswg.worldmind.core.journal.JournalDeliveryReport;
import io.github.melswg.worldmind.core.journal.JournalDeliveryStatus;
import io.github.melswg.worldmind.core.journal.JournalMessageSource;
import io.github.melswg.worldmind.core.journal.JournalParticipationDecision;
import io.github.melswg.worldmind.core.journal.JournalVisibility;
import io.github.melswg.worldmind.core.journal.JournaledBatch;
import io.github.melswg.worldmind.core.journal.JournaledObservation;
import io.github.melswg.worldmind.core.journal.ProviderAttemptOutcome;
import io.github.melswg.worldmind.core.memory.JournalSequenceRange;
import io.github.melswg.worldmind.core.memory.MemoryConfirmation;
import io.github.melswg.worldmind.core.memory.MemoryConfirmationRequest;
import io.github.melswg.worldmind.core.memory.MemoryConfidence;
import io.github.melswg.worldmind.core.memory.MemoryFact;
import io.github.melswg.worldmind.core.memory.MemoryImportance;
import io.github.melswg.worldmind.core.memory.MemoryProvenance;
import io.github.melswg.worldmind.core.memory.MemoryRecord;
import io.github.melswg.worldmind.core.memory.MemoryRecordId;
import io.github.melswg.worldmind.core.memory.MemoryRecordState;
import io.github.melswg.worldmind.core.memory.MemoryScope;
import io.github.melswg.worldmind.core.memory.MemoryVisibility;
import io.github.melswg.worldmind.core.memory.ProposedFactCandidate;
import io.github.melswg.worldmind.core.memory.ProposedMemoryCandidate;
import io.github.melswg.worldmind.core.memory.ProposedRelationshipCandidate;
import io.github.melswg.worldmind.core.memory.RelationshipMemory;
import io.github.melswg.worldmind.core.memory.WorldMemoryRepository;
import io.github.melswg.worldmind.core.memory.WorldMemorySnapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

/** SQLite schema-v1 journal. Every JDBC action is serialized on one private worker thread. */
public final class SqliteDialogueJournal implements DialogueJournal, WorldMemoryRepository {
    public static final String DATABASE_FILE_NAME = "worldmind.sqlite3";
    private static final int SCHEMA_VERSION = 1;

    private final ExecutorService executor;
    private final Connection connection;
    private final WorldIdentity worldIdentity;
    private final AtomicBoolean closing = new AtomicBoolean();

    private SqliteDialogueJournal(ExecutorService executor, Connection connection, WorldIdentity worldIdentity) {
        this.executor = executor;
        this.connection = connection;
        this.worldIdentity = worldIdentity;
    }

    /** Identity loaded during asynchronous open; safe to read after the open stage completed. */
    public WorldIdentity openedWorldIdentity() {
        return worldIdentity;
    }

    public static CompletionStage<SqliteDialogueJournal> open(Path databasePath) {
        Objects.requireNonNull(databasePath, "databasePath");
        ExecutorService executor = Executors.newSingleThreadExecutor(new JournalThreadFactory());
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path absolute = databasePath.toAbsolutePath().normalize();
                Path parent = absolute.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Connection connection = DriverManager.getConnection("jdbc:sqlite:" + absolute);
                try {
                    configureAndInitialize(connection);
                    return new SqliteDialogueJournal(executor, connection, loadWorldIdentity(connection));
                } catch (Throwable failure) {
                    closeConnection(connection);
                    throw failure;
                }
            } catch (IOException | SQLException failure) {
                throw new CompletionException(failure);
            } catch (RuntimeException failure) {
                throw failure;
            }
        }, executor).whenComplete((ignored, failure) -> {
            if (failure != null) {
                executor.shutdownNow();
            }
        });
    }

    @Override
    public CompletionStage<WorldIdentity> worldIdentity() {
        return submit(() -> worldIdentity);
    }

    @Override
    public CompletionStage<JournaledObservation> appendObservation(CapturedPublicChatMessage observation) {
        Objects.requireNonNull(observation, "observation");
        return submit(() -> {
            String sql = "INSERT INTO journal_messages(player_uuid, player_name, message_text, captured_at_epoch_millis, source, visibility, addressing_signal) VALUES (?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, observation.requester().playerId().toString());
                statement.setString(2, observation.requester().playerName());
                statement.setString(3, observation.message());
                statement.setLong(4, observation.capturedAt().toEpochMilli());
                statement.setString(5, JournalMessageSource.PUBLIC_CHAT.name());
                statement.setString(6, JournalVisibility.PUBLIC.name());
                statement.setString(7, observation.addressingSignal().name());
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (!keys.next()) throw new SQLException("SQLite did not return a journal sequence.");
                    return new JournaledObservation(
                        worldIdentity, keys.getLong(1), observation.requester(), observation.message(), observation.capturedAt(),
                        JournalMessageSource.PUBLIC_CHAT, JournalVisibility.PUBLIC, observation.addressingSignal()
                    );
                }
            }
        });
    }

    @Override
    public CompletionStage<JournaledBatch> appendBatch(SealedChatBatch batch) {
        Objects.requireNonNull(batch, "batch");
        return submit(() -> {
            if (!worldIdentity.equals(batch.worldIdentity())) {
                throw new IllegalArgumentException("A journal may only persist batches for its own world.");
            }
            JournaledBatch journaled = JournaledBatch.from(batch, Instant.now());
            withTransaction(() -> {
                try (PreparedStatement insertBatch = connection.prepareStatement(
                    "INSERT INTO journal_batches(batch_id, first_sequence, last_sequence, seal_reason, sealed_at_epoch_millis) VALUES (?, ?, ?, ?, ?)"
                ); PreparedStatement insertMember = connection.prepareStatement(
                    "INSERT INTO journal_batch_messages(batch_id, message_sequence, ordinal) VALUES (?, ?, ?)"
                )) {
                    insertBatch.setString(1, journaled.batchId().toString());
                    insertBatch.setLong(2, journaled.firstSequence());
                    insertBatch.setLong(3, journaled.lastSequence());
                    insertBatch.setString(4, journaled.sealReason().name());
                    insertBatch.setLong(5, journaled.sealedAt().toEpochMilli());
                    insertBatch.executeUpdate();
                    for (int ordinal = 0; ordinal < journaled.messageSequences().size(); ordinal++) {
                        insertMember.setString(1, journaled.batchId().toString());
                        insertMember.setLong(2, journaled.messageSequences().get(ordinal));
                        insertMember.setInt(3, ordinal);
                        insertMember.addBatch();
                    }
                    insertMember.executeBatch();
                    return null;
                }
            });
            return journaled;
        });
    }

    @Override
    public CompletionStage<Void> appendOutcome(JournalBatchOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        return submit(() -> {
            String sql = "INSERT INTO journal_outcomes(batch_id, provider_attempt_outcome, decision, refusal_code, delivery_status, delivered_response, completed_at_epoch_millis) VALUES (?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, outcome.batchId().toString());
                statement.setString(2, outcome.providerAttemptOutcome().name());
                setOptionalString(statement, 3, outcome.decision().map(Enum::name));
                setOptionalString(statement, 4, outcome.refusalCode().map(Enum::name));
                statement.setString(5, outcome.delivery().status().name());
                setOptionalString(statement, 6, outcome.delivery().deliveredResponse());
                statement.setLong(7, outcome.completedAt().toEpochMilli());
                statement.executeUpdate();
                return null;
            }
        });
    }

    @Override
    public CompletionStage<DialogueJournalSnapshot> readSnapshot() {
        return submit(() -> new DialogueJournalSnapshot(worldIdentity, readObservations(), readBatches(), readOutcomes()));
    }

    @Override
    public CompletionStage<List<MemoryRecord>> appendProposed(
        JournaledBatch sourceBatch,
        List<? extends ProposedMemoryCandidate> candidates
    ) {
        Objects.requireNonNull(sourceBatch, "sourceBatch");
        List<? extends ProposedMemoryCandidate> copiedCandidates = List.copyOf(
            Objects.requireNonNull(candidates, "candidates")
        );
        return submit(() -> {
            if (!worldIdentity.equals(sourceBatch.worldIdentity())) {
                throw new IllegalArgumentException("A memory repository may only persist records for its own world.");
            }
            if (copiedCandidates.isEmpty()) {
                return List.of();
            }
            return withTransaction(() -> {
                Map<Long, Instant> sourceTimestamps = verifyPersistedSourceBatch(sourceBatch);
                Instant recordedAt = Instant.now();
                List<MemoryRecord> records = new ArrayList<>(copiedCandidates.size());
                for (ProposedMemoryCandidate candidate : copiedCandidates) {
                    Objects.requireNonNull(candidate, "candidates must not contain null");
                    validateRangeWithinBatch(candidate.sourceRange(), sourceBatch.messageSequences());
                    MemoryRecord record = insertProposed(candidate, sourceBatch.batchId(),
                        sourceTimestamps.get(candidate.sourceRange().lastSequence()), recordedAt);
                    records.add(record);
                }
                return List.copyOf(records);
            });
        });
    }

    @Override
    public CompletionStage<MemoryRecord> confirm(MemoryRecordId recordId, MemoryConfirmationRequest confirmation) {
        Objects.requireNonNull(recordId, "recordId");
        Objects.requireNonNull(confirmation, "confirmation");
        return submit(() -> withTransaction(() -> {
            MemoryRecord existing = readMemoryRecord(recordId).orElseThrow(
                () -> new IllegalArgumentException("Unknown memory record " + recordId.value() + ".")
            );
            if (existing.state() == MemoryRecordState.CONFIRMED) {
                return existing;
            }
            Instant confirmedAt = Instant.now();
            try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO memory_confirmations(record_id, authority, authority_identifier, confirmed_at_epoch_millis) VALUES (?, ?, ?, ?)"
            ); PreparedStatement update = connection.prepareStatement(
                "UPDATE memory_records SET record_state = ? WHERE record_id = ? AND record_state = ?"
            )) {
                insert.setString(1, recordId.value().toString());
                insert.setString(2, confirmation.authority().name());
                insert.setString(3, confirmation.authorityIdentifier());
                insert.setLong(4, confirmedAt.toEpochMilli());
                insert.executeUpdate();
                update.setString(1, MemoryRecordState.CONFIRMED.name());
                update.setString(2, recordId.value().toString());
                update.setString(3, MemoryRecordState.PROPOSED.name());
                if (update.executeUpdate() != 1) {
                    throw new SQLException("Memory record confirmation state changed unexpectedly.");
                }
            }
            return readMemoryRecord(recordId).orElseThrow(() -> new SQLException("Confirmed memory record disappeared."));
        }));
    }

    @Override
    public CompletionStage<List<MemoryRecord>> recallPublic(SealedChatBatch nextBatch) {
        Objects.requireNonNull(nextBatch, "nextBatch");
        return submit(() -> {
            if (!worldIdentity.equals(nextBatch.worldIdentity())) {
                throw new IllegalArgumentException("A memory repository may only recall for its own world.");
            }
            long beforeSequence = nextBatch.messages().get(0).sequence();
            List<String> participantIds = nextBatch.messages().stream()
                .map(message -> message.requester().playerId().toString())
                .distinct()
                .toList();
            String participants = String.join(", ", java.util.Collections.nCopies(participantIds.size(), "?"));
            String sql = "SELECT r.record_id, r.record_type, r.record_state, r.content, r.scope_type, r.scope_player_uuid, "
                + "r.visibility, r.source_batch_id, r.first_sequence, r.last_sequence, r.source_timestamp_epoch_millis, "
                + "r.recorded_at_epoch_millis, r.confidence, r.importance, r.relationship_subject_uuid, "
                + "c.authority, c.authority_identifier, c.confirmed_at_epoch_millis "
                + "FROM memory_records r JOIN memory_confirmations c ON c.record_id = r.record_id "
                + "WHERE r.record_state = ? AND r.visibility = ? AND r.last_sequence < ? "
                + "AND (r.scope_type = 'WORLD' OR (r.scope_type = 'PLAYER' AND r.scope_player_uuid IN (" + participants + "))) "
                + "ORDER BY c.confirmed_at_epoch_millis DESC, r.record_id ASC LIMIT 32";
            List<MemoryRecord> newestFirst = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int parameter = 1;
                statement.setString(parameter++, MemoryRecordState.CONFIRMED.name());
                statement.setString(parameter++, MemoryVisibility.PUBLIC.name());
                statement.setLong(parameter++, beforeSequence);
                for (String participantId : participantIds) {
                    statement.setString(parameter++, participantId);
                }
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) newestFirst.add(memoryRecord(rows));
                }
            }
            newestFirst.sort(java.util.Comparator
                .comparingLong((MemoryRecord record) -> record.provenance().sourceRange().firstSequence())
                .thenComparing(record -> record.id().value()));
            return List.copyOf(newestFirst);
        });
    }

    @Override
    public CompletionStage<WorldMemorySnapshot> readMemorySnapshot() {
        return submit(() -> {
            List<MemoryRecord> records = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT r.record_id, r.record_type, r.record_state, r.content, r.scope_type, r.scope_player_uuid, "
                    + "r.visibility, r.source_batch_id, r.first_sequence, r.last_sequence, r.source_timestamp_epoch_millis, "
                    + "r.recorded_at_epoch_millis, r.confidence, r.importance, r.relationship_subject_uuid, "
                    + "c.authority, c.authority_identifier, c.confirmed_at_epoch_millis "
                    + "FROM memory_records r LEFT JOIN memory_confirmations c ON c.record_id = r.record_id "
                    + "ORDER BY r.recorded_at_epoch_millis, r.record_id"
            ); ResultSet rows = statement.executeQuery()) {
                while (rows.next()) records.add(memoryRecord(rows));
            }
            return new WorldMemorySnapshot(worldIdentity, records);
        });
    }

    @Override
    public CompletionStage<Void> closeAsync() {
        if (!closing.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> closingFuture = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                connection.close();
                closingFuture.complete(null);
            } catch (SQLException failure) {
                closingFuture.completeExceptionally(failure);
            } finally {
                executor.shutdown();
            }
        });
        return closingFuture;
    }

    private List<JournaledObservation> readObservations() throws SQLException {
        List<JournaledObservation> observations = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT sequence, player_uuid, player_name, message_text, captured_at_epoch_millis, source, visibility, addressing_signal FROM journal_messages ORDER BY sequence"
        ); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                observations.add(new JournaledObservation(
                    worldIdentity,
                    rows.getLong("sequence"),
                    new ServerRequester(UUID.fromString(rows.getString("player_uuid")), rows.getString("player_name")),
                    rows.getString("message_text"),
                    Instant.ofEpochMilli(rows.getLong("captured_at_epoch_millis")),
                    JournalMessageSource.valueOf(rows.getString("source")),
                    JournalVisibility.valueOf(rows.getString("visibility")),
                    AddressingSignal.valueOf(rows.getString("addressing_signal"))
                ));
            }
        }
        return observations;
    }

    private List<JournaledBatch> readBatches() throws SQLException {
        List<JournaledBatch> batches = new ArrayList<>();
        try (PreparedStatement batchesQuery = connection.prepareStatement(
            "SELECT batch_id, seal_reason, sealed_at_epoch_millis FROM journal_batches ORDER BY sealed_at_epoch_millis, batch_id"
        ); ResultSet rows = batchesQuery.executeQuery(); PreparedStatement membersQuery = connection.prepareStatement(
            "SELECT message_sequence FROM journal_batch_messages WHERE batch_id = ? ORDER BY ordinal"
        )) {
            while (rows.next()) {
                String batchId = rows.getString("batch_id");
                membersQuery.setString(1, batchId);
                List<Long> sequences = new ArrayList<>();
                try (ResultSet members = membersQuery.executeQuery()) {
                    while (members.next()) sequences.add(members.getLong(1));
                }
                batches.add(new JournaledBatch(
                    UUID.fromString(batchId), worldIdentity, sequences,
                    ChatBatchSealReason.valueOf(rows.getString("seal_reason")),
                    Instant.ofEpochMilli(rows.getLong("sealed_at_epoch_millis"))
                ));
            }
        }
        return batches;
    }

    private Map<UUID, JournalBatchOutcome> readOutcomes() throws SQLException {
        Map<UUID, JournalBatchOutcome> outcomes = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT batch_id, provider_attempt_outcome, decision, refusal_code, delivery_status, delivered_response, completed_at_epoch_millis FROM journal_outcomes ORDER BY completed_at_epoch_millis, batch_id"
        ); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                UUID batchId = UUID.fromString(rows.getString("batch_id"));
                outcomes.put(batchId, new JournalBatchOutcome(
                    batchId,
                    ProviderAttemptOutcome.valueOf(rows.getString("provider_attempt_outcome")),
                    optionalEnum(rows, "decision", JournalParticipationDecision::valueOf),
                    optionalEnum(rows, "refusal_code", RefusalCode::valueOf),
                    new JournalDeliveryReport(
                        JournalDeliveryStatus.valueOf(rows.getString("delivery_status")),
                        Optional.ofNullable(rows.getString("delivered_response"))
                    ),
                    Instant.ofEpochMilli(rows.getLong("completed_at_epoch_millis"))
                ));
            }
        }
        return outcomes;
    }

    private MemoryRecord insertProposed(
        ProposedMemoryCandidate candidate,
        UUID sourceBatchId,
        Instant sourceTimestamp,
        Instant recordedAt
    ) throws SQLException {
        MemoryRecordId id = new MemoryRecordId(UUID.randomUUID());
        String recordType;
        String content;
        String relationshipSubjectId;
        if (candidate instanceof ProposedFactCandidate fact) {
            recordType = "FACT";
            content = fact.content();
            relationshipSubjectId = null;
        } else if (candidate instanceof ProposedRelationshipCandidate relationship) {
            recordType = "RELATIONSHIP";
            content = relationship.relationshipState();
            relationshipSubjectId = relationship.subjectPlayerId().toString();
        } else {
            throw new IllegalArgumentException("Unsupported proposed memory candidate type.");
        }
        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO memory_records(record_id, record_type, record_state, content, scope_type, scope_player_uuid, visibility, "
                + "source_batch_id, first_sequence, last_sequence, source_timestamp_epoch_millis, recorded_at_epoch_millis, confidence, importance, relationship_subject_uuid) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        )) {
            statement.setString(1, id.value().toString());
            statement.setString(2, recordType);
            statement.setString(3, MemoryRecordState.PROPOSED.name());
            statement.setString(4, content);
            statement.setString(5, scopeType(candidate.scope()));
            setOptionalString(statement, 6, scopePlayerId(candidate.scope()));
            statement.setString(7, candidate.visibility().name());
            statement.setString(8, sourceBatchId.toString());
            statement.setLong(9, candidate.sourceRange().firstSequence());
            statement.setLong(10, candidate.sourceRange().lastSequence());
            statement.setLong(11, sourceTimestamp.toEpochMilli());
            statement.setLong(12, recordedAt.toEpochMilli());
            statement.setDouble(13, candidate.confidence().value());
            statement.setDouble(14, candidate.importance().value());
            setOptionalString(statement, 15, Optional.ofNullable(relationshipSubjectId));
            statement.executeUpdate();
        }
        return readMemoryRecord(id).orElseThrow(() -> new SQLException("Inserted memory record disappeared."));
    }

    private Map<Long, Instant> verifyPersistedSourceBatch(JournaledBatch sourceBatch) throws SQLException {
        Map<Long, Instant> timestamps = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT m.message_sequence, j.captured_at_epoch_millis FROM journal_batch_messages m "
                + "JOIN journal_messages j ON j.sequence = m.message_sequence "
                + "WHERE m.batch_id = ? ORDER BY m.ordinal"
        )) {
            statement.setString(1, sourceBatch.batchId().toString());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    timestamps.put(rows.getLong("message_sequence"), Instant.ofEpochMilli(rows.getLong("captured_at_epoch_millis")));
                }
            }
        }
        if (!sourceBatch.messageSequences().equals(List.copyOf(timestamps.keySet()))) {
            throw new IllegalArgumentException("Memory provenance must refer to the persisted source batch membership.");
        }
        return timestamps;
    }

    private static void validateRangeWithinBatch(JournalSequenceRange range, List<Long> batchSequences) {
        long expected = range.firstSequence();
        for (Long sequence : batchSequences) {
            if (sequence < range.firstSequence()) continue;
            if (sequence > range.lastSequence()) break;
            if (sequence != expected) {
                throw new IllegalArgumentException("Memory provenance range must be contiguous within one sealed batch.");
            }
            if (sequence == range.lastSequence()) return;
            expected++;
        }
        throw new IllegalArgumentException("Memory provenance range is not contained in the sealed batch.");
    }

    private Optional<MemoryRecord> readMemoryRecord(MemoryRecordId id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT r.record_id, r.record_type, r.record_state, r.content, r.scope_type, r.scope_player_uuid, "
                + "r.visibility, r.source_batch_id, r.first_sequence, r.last_sequence, r.source_timestamp_epoch_millis, "
                + "r.recorded_at_epoch_millis, r.confidence, r.importance, r.relationship_subject_uuid, "
                + "c.authority, c.authority_identifier, c.confirmed_at_epoch_millis "
                + "FROM memory_records r LEFT JOIN memory_confirmations c ON c.record_id = r.record_id WHERE r.record_id = ?"
        )) {
            statement.setString(1, id.value().toString());
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(memoryRecord(row)) : Optional.empty();
            }
        }
    }

    private static MemoryRecord memoryRecord(ResultSet row) throws SQLException {
        MemoryRecordId id = new MemoryRecordId(UUID.fromString(row.getString("record_id")));
        MemoryRecordState state = MemoryRecordState.valueOf(row.getString("record_state"));
        MemoryScope scope = switch (row.getString("scope_type")) {
            case "WORLD" -> MemoryScope.world();
            case "PLAYER" -> MemoryScope.player(UUID.fromString(row.getString("scope_player_uuid")));
            default -> throw new SQLException("Unknown memory scope type.");
        };
        MemoryProvenance provenance = new MemoryProvenance(
            UUID.fromString(row.getString("source_batch_id")),
            new JournalSequenceRange(row.getLong("first_sequence"), row.getLong("last_sequence"))
        );
        String authority = row.getString("authority");
        Optional<MemoryConfirmation> confirmation = authority == null ? Optional.empty() : Optional.of(new MemoryConfirmation(
            io.github.melswg.worldmind.core.memory.MemoryConfirmationAuthority.valueOf(authority),
            row.getString("authority_identifier"),
            Instant.ofEpochMilli(row.getLong("confirmed_at_epoch_millis"))
        ));
        Instant sourceTimestamp = Instant.ofEpochMilli(row.getLong("source_timestamp_epoch_millis"));
        Instant recordedAt = Instant.ofEpochMilli(row.getLong("recorded_at_epoch_millis"));
        MemoryConfidence confidence = new MemoryConfidence(row.getDouble("confidence"));
        MemoryImportance importance = new MemoryImportance(row.getDouble("importance"));
        MemoryVisibility visibility = MemoryVisibility.valueOf(row.getString("visibility"));
        String type = row.getString("record_type");
        if ("FACT".equals(type)) {
            return new MemoryFact(id, state, scope, visibility, provenance, sourceTimestamp, recordedAt, confidence, importance,
                confirmation, row.getString("content"));
        }
        if ("RELATIONSHIP".equals(type)) {
            return new RelationshipMemory(id, state, scope, visibility, provenance, sourceTimestamp, recordedAt, confidence,
                importance, confirmation, UUID.fromString(row.getString("relationship_subject_uuid")), row.getString("content"));
        }
        throw new SQLException("Unknown memory record type.");
    }

    private static String scopeType(MemoryScope scope) {
        return scope instanceof MemoryScope.World ? "WORLD" : "PLAYER";
    }

    private static Optional<String> scopePlayerId(MemoryScope scope) {
        return scope instanceof MemoryScope.Player player
            ? Optional.of(player.playerId().toString())
            : Optional.empty();
    }

    private <T> CompletionStage<T> submit(SqlSupplier<T> action) {
        if (closing.get()) return CompletableFuture.failedFuture(new IllegalStateException("Dialogue journal is closing."));
        return CompletableFuture.supplyAsync(() -> {
            try {
                return action.get();
            } catch (SQLException failure) {
                throw new CompletionException(failure);
            }
        }, executor);
    }

    private static void configureAndInitialize(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA synchronous = FULL");
            statement.execute("CREATE TABLE IF NOT EXISTS journal_metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
        }
        String version = metadata(connection, "schema_version");
        if (version == null) {
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO journal_metadata(key, value) VALUES (?, ?)") ) {
                insert.setString(1, "schema_version"); insert.setString(2, Integer.toString(SCHEMA_VERSION)); insert.executeUpdate();
                insert.setString(1, "world_id"); insert.setString(2, UUID.randomUUID().toString()); insert.executeUpdate();
            }
            createSchemaV1(connection);
        } else if (Integer.parseInt(version) != SCHEMA_VERSION) {
            throw new SqliteJournalSchemaException("Unsupported SQLite journal schema version " + version + ".");
        } else {
            createSchemaV1(connection);
        }
    }

    private static void createSchemaV1(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS journal_messages (sequence INTEGER PRIMARY KEY AUTOINCREMENT, player_uuid TEXT NOT NULL, player_name TEXT NOT NULL, message_text TEXT NOT NULL, captured_at_epoch_millis INTEGER NOT NULL, source TEXT NOT NULL, visibility TEXT NOT NULL, addressing_signal TEXT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS journal_batches (batch_id TEXT PRIMARY KEY, first_sequence INTEGER NOT NULL, last_sequence INTEGER NOT NULL, seal_reason TEXT NOT NULL, sealed_at_epoch_millis INTEGER NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS journal_batch_messages (batch_id TEXT NOT NULL REFERENCES journal_batches(batch_id), message_sequence INTEGER NOT NULL REFERENCES journal_messages(sequence), ordinal INTEGER NOT NULL, PRIMARY KEY(batch_id, message_sequence), UNIQUE(batch_id, ordinal))");
            statement.execute("CREATE TABLE IF NOT EXISTS journal_outcomes (batch_id TEXT PRIMARY KEY REFERENCES journal_batches(batch_id), provider_attempt_outcome TEXT NOT NULL, decision TEXT, refusal_code TEXT, delivery_status TEXT NOT NULL, delivered_response TEXT, completed_at_epoch_millis INTEGER NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS memory_records (record_id TEXT PRIMARY KEY, record_type TEXT NOT NULL CHECK(record_type IN ('FACT', 'RELATIONSHIP')), record_state TEXT NOT NULL CHECK(record_state IN ('PROPOSED', 'CONFIRMED')), content TEXT NOT NULL CHECK(length(trim(content)) > 0), scope_type TEXT NOT NULL CHECK(scope_type IN ('WORLD', 'PLAYER')), scope_player_uuid TEXT, visibility TEXT NOT NULL CHECK(visibility IN ('PUBLIC', 'PRIVATE')), source_batch_id TEXT NOT NULL REFERENCES journal_batches(batch_id), first_sequence INTEGER NOT NULL, last_sequence INTEGER NOT NULL, source_timestamp_epoch_millis INTEGER NOT NULL, recorded_at_epoch_millis INTEGER NOT NULL, confidence REAL NOT NULL CHECK(confidence >= 0.0 AND confidence <= 1.0), importance REAL NOT NULL CHECK(importance >= 0.0 AND importance <= 1.0), relationship_subject_uuid TEXT, CHECK((scope_type = 'WORLD' AND scope_player_uuid IS NULL) OR (scope_type = 'PLAYER' AND scope_player_uuid IS NOT NULL)), CHECK((record_type = 'FACT' AND relationship_subject_uuid IS NULL) OR (record_type = 'RELATIONSHIP' AND relationship_subject_uuid IS NOT NULL)), CHECK(last_sequence >= first_sequence))");
            statement.execute("CREATE TABLE IF NOT EXISTS memory_confirmations (record_id TEXT PRIMARY KEY REFERENCES memory_records(record_id), authority TEXT NOT NULL CHECK(authority IN ('DETERMINISTIC_POLICY', 'AUTHORIZED_OPERATOR')), authority_identifier TEXT NOT NULL CHECK(length(trim(authority_identifier)) > 0), confirmed_at_epoch_millis INTEGER NOT NULL)");
            statement.execute("CREATE INDEX IF NOT EXISTS memory_records_public_recall_idx ON memory_records(record_state, visibility, last_sequence, scope_type, scope_player_uuid)");
            statement.execute("CREATE INDEX IF NOT EXISTS memory_records_source_batch_idx ON memory_records(source_batch_id, first_sequence, last_sequence)");
            statement.execute("CREATE INDEX IF NOT EXISTS memory_confirmations_recall_idx ON memory_confirmations(confirmed_at_epoch_millis, record_id)");
        }
    }

    private static WorldIdentity loadWorldIdentity(Connection connection) throws SQLException {
        String worldId = metadata(connection, "world_id");
        if (worldId == null || worldId.isBlank()) throw new SqliteJournalSchemaException("Journal metadata has no world identity.");
        return new WorldIdentity("world-" + worldId);
    }

    private static String metadata(Connection connection, String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT value FROM journal_metadata WHERE key = ?")) {
            statement.setString(1, key);
            try (ResultSet row = statement.executeQuery()) { return row.next() ? row.getString(1) : null; }
        }
    }

    private static void setOptionalString(PreparedStatement statement, int parameter, Optional<String> value) throws SQLException {
        if (value.isPresent()) statement.setString(parameter, value.get()); else statement.setNull(parameter, java.sql.Types.VARCHAR);
    }

    private static <T> Optional<T> optionalEnum(ResultSet rows, String column, java.util.function.Function<String, T> parser) throws SQLException {
        String value = rows.getString(column);
        return value == null ? Optional.empty() : Optional.of(parser.apply(value));
    }

    private <T> T withTransaction(SqlSupplier<T> work) throws SQLException {
        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            T result = work.get();
            connection.commit();
            return result;
        } catch (SQLException | RuntimeException failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(originalAutoCommit);
        }
    }

    private static void closeConnection(Connection connection) {
        try { connection.close(); } catch (SQLException ignored) { }
    }

    @FunctionalInterface
    private interface SqlSupplier<T> { T get() throws SQLException; }

    private static final class JournalThreadFactory implements ThreadFactory {
        @Override public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "worldmind-sqlite-journal");
            thread.setDaemon(true);
            return thread;
        }
    }
}
