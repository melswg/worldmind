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
import io.github.melswg.worldmind.core.configuration.SecretRedactionPolicy;
import io.github.melswg.worldmind.core.memory.JournalSequenceRange;
import io.github.melswg.worldmind.core.memory.CompactionSource;
import io.github.melswg.worldmind.core.memory.CurrentSituationVersion;
import io.github.melswg.worldmind.core.memory.DerivedMemoryCandidate;
import io.github.melswg.worldmind.core.memory.DerivedMemoryProvenance;
import io.github.melswg.worldmind.core.memory.MemoryChunkSummary;
import io.github.melswg.worldmind.core.memory.MemoryCompactionInput;
import io.github.melswg.worldmind.core.memory.MemoryCompactionRepository;
import io.github.melswg.worldmind.core.memory.MemoryCompactionResult;
import io.github.melswg.worldmind.core.memory.MemoryCompactionSnapshot;
import io.github.melswg.worldmind.core.memory.MemoryEvent;
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
import io.github.melswg.worldmind.core.memory.MemoryRetrievalRequest;
import io.github.melswg.worldmind.core.memory.RetrievedMemoryContext;
import io.github.melswg.worldmind.core.memory.RetrievedMemoryEntry;
import io.github.melswg.worldmind.core.memory.RetrievedMemoryRecordType;
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
public final class SqliteDialogueJournal implements DialogueJournal, WorldMemoryRepository, MemoryCompactionRepository {
    public static final String DATABASE_FILE_NAME = "worldmind.sqlite3";
    private static final int SCHEMA_VERSION = 1;
    private static final int RECENT_WORKING_OBSERVATIONS = 24;
    private static final int MAX_COMPACTION_BATCHES = 8;
    private static final int MAX_COMPACTION_MESSAGES = 32;
    private static final int MAX_COMPACTION_CODE_POINTS = 12_000;

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
        String redactedMessage = SecretRedactionPolicy.redact(observation.message());
        return submit(() -> {
            String sql = "INSERT INTO journal_messages(player_uuid, player_name, message_text, captured_at_epoch_millis, source, visibility, addressing_signal) VALUES (?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, observation.requester().playerId().toString());
                statement.setString(2, observation.requester().playerName());
                statement.setString(3, redactedMessage);
                statement.setLong(4, observation.capturedAt().toEpochMilli());
                statement.setString(5, JournalMessageSource.PUBLIC_CHAT.name());
                statement.setString(6, JournalVisibility.PUBLIC.name());
                statement.setString(7, observation.addressingSignal().name());
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (!keys.next()) throw new SQLException("SQLite did not return a journal sequence.");
                    return new JournaledObservation(
                        worldIdentity, keys.getLong(1), observation.requester(), redactedMessage, observation.capturedAt(),
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
    public CompletionStage<io.github.melswg.worldmind.core.memory.RetrievedMemoryContext> retrievePublic(
        io.github.melswg.worldmind.core.memory.MemoryRetrievalRequest request
    ) {
        Objects.requireNonNull(request, "request");
        return submit(() -> retrievePublicContext(request));
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

    private RetrievedMemoryContext retrievePublicContext(MemoryRetrievalRequest request) throws SQLException {
        SealedChatBatch nextBatch = request.chatBatch();
        if (!worldIdentity.equals(nextBatch.worldIdentity())) {
            throw new IllegalArgumentException("A memory repository may only retrieve for its own world.");
        }
        rebuildSearchDocuments(connection);
        long beforeSequence = nextBatch.messages().get(0).sequence();
        List<String> participants = nextBatch.messages().stream()
            .map(message -> message.requester().playerId().toString()).distinct().toList();
        List<RetrievedMemoryEntry> recent = recentDialogue(beforeSequence);
        List<RetrievedMemoryEntry> situations = currentSituations(beforeSequence, participants);
        List<RankedSearchDocument> older = rankedOlderDocuments(nextBatch, beforeSequence, participants);
        return applyRetrievalBudget(recent, situations, older.stream().map(RankedSearchDocument::entry).toList());
    }

    private List<RetrievedMemoryEntry> recentDialogue(long beforeSequence) throws SQLException {
        List<RetrievedMemoryEntry> newestFirst = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT * FROM memory_search_documents d WHERE d.record_type = 'DIALOGUE' AND d.visibility = ? AND d.last_sequence < ? "
                + "AND NOT EXISTS (SELECT 1 FROM memory_compaction_coverage c WHERE d.last_sequence BETWEEN c.first_sequence AND c.last_sequence) "
                + "ORDER BY d.last_sequence DESC, d.stable_identity ASC LIMIT 12"
        )) {
            statement.setString(1, MemoryVisibility.PUBLIC.name());
            statement.setLong(2, beforeSequence);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) newestFirst.add(retrievedEntry(searchDocument(rows)));
            }
        }
        java.util.Collections.reverse(newestFirst);
        return List.copyOf(newestFirst);
    }

    private List<RetrievedMemoryEntry> currentSituations(long beforeSequence, List<String> participants) throws SQLException {
        String scope = String.join(", ", java.util.Collections.nCopies(participants.size(), "?"));
        String sql = "SELECT s.* FROM memory_current_situation_versions s WHERE s.visibility = ? AND s.last_sequence < ? "
            + "AND (s.scope_type = 'WORLD' OR (s.scope_type = 'PLAYER' AND s.scope_player_uuid IN (" + scope + "))) "
            + "AND NOT EXISTS (SELECT 1 FROM memory_current_situation_versions newer WHERE newer.situation_series_id = s.situation_series_id "
            + "AND newer.version_number > s.version_number) ORDER BY s.recorded_at_epoch_millis DESC, s.situation_version_id ASC LIMIT 4";
        List<RetrievedMemoryEntry> entries = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int parameter = 1;
            statement.setString(parameter++, MemoryVisibility.PUBLIC.name());
            statement.setLong(parameter++, beforeSequence);
            for (String participant : participants) statement.setString(parameter++, participant);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID versionId = UUID.fromString(rows.getString("situation_version_id"));
                    SearchDocument document = new SearchDocument(
                        RetrievedMemoryRecordType.CURRENT_SITUATION, versionId.toString(), rows.getString("content"), scope(rows),
                        MemoryVisibility.valueOf(rows.getString("visibility")), range(rows),
                        Instant.ofEpochMilli(rows.getLong("source_timestamp_epoch_millis")),
                        Instant.ofEpochMilli(rows.getLong("recorded_at_epoch_millis")), new MemoryConfidence(rows.getDouble("confidence")),
                        new MemoryImportance(rows.getDouble("importance")), derivedBatchIds("CURRENT_SITUATION", versionId)
                    );
                    entries.add(retrievedEntry(document));
                }
            }
        }
        entries.sort(java.util.Comparator.comparing(RetrievedMemoryEntry::recordedAt).thenComparing(RetrievedMemoryEntry::identity));
        return List.copyOf(entries);
    }

    private List<RankedSearchDocument> rankedOlderDocuments(
        SealedChatBatch nextBatch,
        long beforeSequence,
        List<String> participants
    ) throws SQLException {
        String query = ftsQuery(nextBatch);
        if (query.isEmpty()) return List.of();
        String scope = String.join(", ", java.util.Collections.nCopies(participants.size(), "?"));
        String sql = "SELECT d.*, bm25(memory_search_fts) AS bm25_score FROM memory_search_fts "
            + "JOIN memory_search_documents d ON d.document_id = memory_search_fts.rowid "
            + "WHERE memory_search_fts MATCH ? AND d.visibility = ? AND d.last_sequence < ? "
            + "AND (d.scope_type = 'WORLD' OR (d.scope_type = 'PLAYER' AND d.scope_player_uuid IN (" + scope + "))) "
            + "ORDER BY bm25(memory_search_fts), d.last_sequence DESC, d.record_type, d.stable_identity LIMIT 64";
        List<RankedSearchDocument> candidates = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int parameter = 1;
            statement.setString(parameter++, query);
            statement.setString(parameter++, MemoryVisibility.PUBLIC.name());
            statement.setLong(parameter++, beforeSequence);
            for (String participant : participants) statement.setString(parameter++, participant);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    SearchDocument document = searchDocument(rows);
                    double relevance = 1.0 / (1.0 + Math.abs(rows.getDouble("bm25_score")));
                    double recency = Math.max(0.0, Math.min(1.0, 1.0 - ((double) (beforeSequence - document.range.lastSequence()) / 10_000.0)));
                    candidates.add(new RankedSearchDocument(retrievedEntry(document), 0.70 * relevance + 0.20 * recency
                        + 0.10 * document.importance.value()));
                }
            }
        }
        candidates.sort(java.util.Comparator.comparingDouble(RankedSearchDocument::score).reversed()
            .thenComparing((RankedSearchDocument value) -> value.entry.provenance().sourceRange().lastSequence(), java.util.Comparator.reverseOrder())
            .thenComparing(value -> value.entry.type())
            .thenComparing(value -> value.entry.identity()));
        return candidates.stream().limit(12).toList();
    }

    private RetrievedMemoryContext applyRetrievalBudget(
        List<RetrievedMemoryEntry> recent,
        List<RetrievedMemoryEntry> situations,
        List<RetrievedMemoryEntry> older
    ) {
        List<RetrievedMemoryEntry> selectedRecent = new ArrayList<>();
        List<RetrievedMemoryEntry> selectedSituations = new ArrayList<>();
        List<RetrievedMemoryEntry> selectedOlder = new ArrayList<>();
        int[] used = {0};
        // Preserve the newest current view before spending the finite memory budget on dialogue history.
        addWithinBudget(situations, selectedSituations, used);
        addWithinBudget(recent, selectedRecent, used, 700);
        addWithinBudget(older, selectedOlder, used);
        return new RetrievedMemoryContext(selectedRecent, selectedSituations, selectedOlder);
    }

    private void addWithinBudget(List<RetrievedMemoryEntry> source, List<RetrievedMemoryEntry> target, int[] used) {
        addWithinBudget(source, target, used, 0);
    }

    private void addWithinBudget(List<RetrievedMemoryEntry> source, List<RetrievedMemoryEntry> target, int[] used, int reservedForLater) {
        for (RetrievedMemoryEntry entry : source) {
            int cost = serializedMemoryCost(entry);
            if (used[0] + cost > RetrievedMemoryContext.MAX_SERIALIZED_CODE_POINTS - reservedForLater) break;
            target.add(entry);
            used[0] += cost;
        }
    }

    private static int serializedMemoryCost(RetrievedMemoryEntry entry) {
        return 96 + entry.type().name().length() + entry.identity().toString().length()
            + entry.content().codePointCount(0, entry.content().length())
            + entry.provenance().sourceBatchIds().size() * 38;
    }

    private SearchDocument searchDocument(ResultSet row) throws SQLException {
        return new SearchDocument(
            RetrievedMemoryRecordType.valueOf(row.getString("record_type")), row.getString("stable_identity"), row.getString("content"),
            scope(row), MemoryVisibility.valueOf(row.getString("visibility")), range(row),
            Instant.ofEpochMilli(row.getLong("source_timestamp_epoch_millis")), Instant.ofEpochMilli(row.getLong("recorded_at_epoch_millis")),
            new MemoryConfidence(row.getDouble("confidence")), new MemoryImportance(row.getDouble("importance")),
            parseBatchIds(row.getString("source_batch_ids"))
        );
    }

    private RetrievedMemoryEntry retrievedEntry(SearchDocument document) {
        return new RetrievedMemoryEntry(document.type, stableIdentity(document.stableIdentity),
            new DerivedMemoryProvenance(document.range, document.sourceBatchIds), document.sourceTimestamp, document.recordedAt,
            document.confidence, document.importance, document.scope, document.visibility, truncateCodePoints(document.content, 600));
    }

    private static UUID stableIdentity(String stableIdentity) {
        int colon = stableIdentity.indexOf(':');
        String value = colon < 0 ? stableIdentity : stableIdentity.substring(colon + 1);
        try { return UUID.fromString(value); }
        catch (IllegalArgumentException ignored) { return UUID.nameUUIDFromBytes(stableIdentity.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    }

    private static List<UUID> parseBatchIds(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Public search document has no source batch.");
        return java.util.Arrays.stream(value.split(",", -1)).map(UUID::fromString).toList();
    }

    private static String truncateCodePoints(String value, int maximum) {
        if (value.codePointCount(0, value.length()) <= maximum) return value;
        return value.substring(0, value.offsetByCodePoints(0, maximum - 1)) + "…";
    }

    private static String ftsQuery(SealedChatBatch batch) {
        java.util.LinkedHashSet<String> terms = new java.util.LinkedHashSet<>();
        String joined = batch.messages().stream().map(message -> message.message()).collect(java.util.stream.Collectors.joining(" "));
        for (String term : joined.toLowerCase(java.util.Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (term.codePointCount(0, term.length()) >= 2 && terms.size() < 16) terms.add(term);
        }
        String query = terms.stream().map(term -> "\"" + term.replace("\"", "") + "\"").collect(java.util.stream.Collectors.joining(" OR "));
        return query.codePointCount(0, query.length()) <= 256 ? query : truncateCodePoints(query, 256);
    }

    private List<UUID> derivedBatchIds(String kind, UUID recordId) throws SQLException {
        List<UUID> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT batch_id FROM memory_derived_sources WHERE record_kind = ? AND record_id = ? ORDER BY ordinal"
        )) {
            statement.setString(1, kind); statement.setString(2, recordId.toString());
            try (ResultSet rows = statement.executeQuery()) { while (rows.next()) result.add(UUID.fromString(rows.getString(1))); }
        }
        return List.copyOf(result);
    }

    private record SearchDocument(
        RetrievedMemoryRecordType type, String stableIdentity, String content, MemoryScope scope, MemoryVisibility visibility,
        JournalSequenceRange range, Instant sourceTimestamp, Instant recordedAt, MemoryConfidence confidence,
        MemoryImportance importance, List<UUID> sourceBatchIds
    ) { }

    private record RankedSearchDocument(RetrievedMemoryEntry entry, double score) { }

    @Override
    public CompletionStage<Optional<MemoryCompactionInput>> nextCompaction(WorldIdentity requestedWorld) {
        Objects.requireNonNull(requestedWorld, "requestedWorld");
        return submit(() -> {
            if (!worldIdentity.equals(requestedWorld)) {
                throw new IllegalArgumentException("A compaction repository may only compact its own world.");
            }
            Long cutoff = sequenceBeforeRecentWorkingWindow();
            if (cutoff == null) return Optional.empty();

            Map<UUID, JournalBatchOutcome> outcomes = readOutcomes();
            List<JournaledBatch> completed = readBatches().stream()
                .filter(batch -> batch.lastSequence() <= cutoff && outcomes.containsKey(batch.batchId()))
                .toList();
            for (int start = 0; start < completed.size(); start++) {
                List<JournaledBatch> selected = new ArrayList<>();
                long expectedFirst = -1;
                int messageCount = 0;
                int sourceCodePoints = 0;
                for (int index = start; index < completed.size() && selected.size() < MAX_COMPACTION_BATCHES; index++) {
                    JournaledBatch batch = completed.get(index);
                    if (isCompactionRangeCovered(new JournalSequenceRange(batch.firstSequence(), batch.lastSequence()))) {
                        break;
                    }
                    if (expectedFirst != -1 && batch.firstSequence() != expectedFirst) break;
                    int nextMessageCount = messageCount + batch.messageSequences().size();
                    if (nextMessageCount > MAX_COMPACTION_MESSAGES) break;
                    List<CompactionSource> batchSources = readCompactionSources(batch.firstSequence(), batch.lastSequence());
                    if (batchSources.size() != batch.messageSequences().size()) break;
                    int nextCodePoints = sourceCodePoints + batchSources.stream()
                        .mapToInt(source -> source.text().codePointCount(0, source.text().length())).sum();
                    if (nextCodePoints > MAX_COMPACTION_CODE_POINTS) break;
                    selected.add(batch);
                    messageCount = nextMessageCount;
                    sourceCodePoints = nextCodePoints;
                    expectedFirst = batch.lastSequence() + 1;
                }
                if (!selected.isEmpty()) {
                    long first = selected.get(0).firstSequence();
                    long last = selected.get(selected.size() - 1).lastSequence();
                    List<CompactionSource> sources = readCompactionSources(first, last);
                    if (sources.size() == last - first + 1) {
                        return Optional.of(new MemoryCompactionInput(
                            worldIdentity,
                            new DerivedMemoryProvenance(new JournalSequenceRange(first, last), selected.stream().map(JournaledBatch::batchId).toList()),
                            sources
                        ));
                    }
                }
            }
            return Optional.empty();
        });
    }

    @Override
    public CompletionStage<MemoryCompactionSnapshot> persistCompaction(
        MemoryCompactionInput input,
        MemoryCompactionResult result
    ) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(result, "result");
        return submit(() -> {
            if (!worldIdentity.equals(input.worldIdentity())) {
                throw new IllegalArgumentException("A compaction repository may only persist its own world.");
            }
            validateCompactionResult(result);
            return withTransaction(() -> {
                validatePersistedCompactionInput(input);
                Instant sourceTimestamp = input.sources().get(input.sources().size() - 1).capturedAt();
                Instant recordedAt = Instant.now();
                for (DerivedMemoryCandidate event : result.events()) {
                    insertOrReadEvent(input.provenance(), event, sourceTimestamp, recordedAt);
                }
                if (result.summary().isPresent()) {
                    insertSummary(input.provenance(), result.summary().orElseThrow(), sourceTimestamp, recordedAt);
                }
                if (result.currentSituation().isPresent()) {
                    insertCurrentSituation(input.provenance(), result.currentSituation().orElseThrow(), sourceTimestamp, recordedAt);
                }
                try (PreparedStatement coverage = connection.prepareStatement(
                    "INSERT OR IGNORE INTO memory_compaction_coverage(first_sequence, last_sequence, recorded_at_epoch_millis) VALUES (?, ?, ?)"
                )) {
                    coverage.setLong(1, input.provenance().sourceRange().firstSequence());
                    coverage.setLong(2, input.provenance().sourceRange().lastSequence());
                    coverage.setLong(3, recordedAt.toEpochMilli());
                    coverage.executeUpdate();
                }
                return loadCompactionSnapshot();
            });
        });
    }

    @Override
    public CompletionStage<MemoryCompactionSnapshot> readCompactionSnapshot() {
        return submit(this::loadCompactionSnapshot);
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
            "SELECT batch_id, seal_reason, sealed_at_epoch_millis FROM journal_batches ORDER BY first_sequence, batch_id"
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

    private Long sequenceBeforeRecentWorkingWindow() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT sequence FROM journal_messages ORDER BY sequence DESC LIMIT 1 OFFSET ?"
        )) {
            statement.setInt(1, RECENT_WORKING_OBSERVATIONS);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getLong(1) : null;
            }
        }
    }

    private boolean isCompactionRangeCovered(JournalSequenceRange range) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT 1 FROM memory_compaction_coverage WHERE NOT (last_sequence < ? OR first_sequence > ?) LIMIT 1"
        )) {
            statement.setLong(1, range.firstSequence());
            statement.setLong(2, range.lastSequence());
            try (ResultSet row = statement.executeQuery()) {
                return row.next();
            }
        }
    }

    private List<CompactionSource> readCompactionSources(long firstSequence, long lastSequence) throws SQLException {
        List<CompactionSource> sources = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT sequence, player_uuid, player_name, message_text, captured_at_epoch_millis FROM journal_messages "
                + "WHERE sequence >= ? AND sequence <= ? AND visibility = ? ORDER BY sequence"
        )) {
            statement.setLong(1, firstSequence);
            statement.setLong(2, lastSequence);
            statement.setString(3, JournalVisibility.PUBLIC.name());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    sources.add(new CompactionSource(
                        rows.getLong("sequence"),
                        new ServerRequester(UUID.fromString(rows.getString("player_uuid")), rows.getString("player_name")),
                        rows.getString("message_text"),
                        Instant.ofEpochMilli(rows.getLong("captured_at_epoch_millis"))
                    ));
                }
            }
        }
        return sources;
    }

    private void validatePersistedCompactionInput(MemoryCompactionInput input) throws SQLException {
        JournalSequenceRange range = input.provenance().sourceRange();
        Long cutoff = sequenceBeforeRecentWorkingWindow();
        if (!isCompactionRangeCovered(range) && (cutoff == null || range.lastSequence() > cutoff)) {
            throw new IllegalArgumentException("Compaction must preserve the recent working window.");
        }
        List<CompactionSource> persisted = readCompactionSources(range.firstSequence(), range.lastSequence());
        if (!persisted.equals(input.sources())) {
            throw new IllegalArgumentException("Compaction input must exactly match persisted public raw dialogue.");
        }
        List<UUID> verifiedBatches = new ArrayList<>();
        long expected = range.firstSequence();
        for (UUID batchId : input.provenance().sourceBatchIds()) {
            JournaledBatch batch = readBatches().stream().filter(value -> value.batchId().equals(batchId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Compaction provenance references an unknown batch."));
            if (!readOutcomes().containsKey(batchId) || batch.firstSequence() != expected) {
                throw new IllegalArgumentException("Compaction provenance must reference contiguous completed batches.");
            }
            expected = batch.lastSequence() + 1;
            verifiedBatches.add(batchId);
        }
        if (expected - 1 != range.lastSequence() || !verifiedBatches.equals(input.provenance().sourceBatchIds())) {
            throw new IllegalArgumentException("Compaction provenance must exactly cover its raw range.");
        }
    }

    private static void validateCompactionResult(MemoryCompactionResult result) {
        for (DerivedMemoryCandidate event : result.events()) {
            MemoryEvent.requireBoundedContent(event.content(), 600, "event");
        }
        result.summary().ifPresent(value -> MemoryEvent.requireBoundedContent(value.content(), 1_200, "summary"));
        result.currentSituation().ifPresent(value -> MemoryEvent.requireBoundedContent(value.content(), 1_200, "current situation"));
    }

    private MemoryEvent insertOrReadEvent(
        DerivedMemoryProvenance provenance,
        DerivedMemoryCandidate candidate,
        Instant sourceTimestamp,
        Instant recordedAt
    ) throws SQLException {
        try (PreparedStatement existing = connection.prepareStatement(
            "SELECT event_id, source_timestamp_epoch_millis, recorded_at_epoch_millis FROM memory_events "
                + "WHERE content = ? AND scope_type = ? AND COALESCE(scope_player_uuid, '') = COALESCE(?, '') "
                + "AND visibility = ? AND first_sequence = ? AND last_sequence = ? ORDER BY event_id LIMIT 1"
        )) {
            existing.setString(1, candidate.content());
            existing.setString(2, scopeType(candidate.scope()));
            setOptionalString(existing, 3, scopePlayerId(candidate.scope()));
            existing.setString(4, candidate.visibility().name());
            existing.setLong(5, provenance.sourceRange().firstSequence());
            existing.setLong(6, provenance.sourceRange().lastSequence());
            try (ResultSet row = existing.executeQuery()) {
                if (row.next()) {
                    UUID id = UUID.fromString(row.getString("event_id"));
                    return new MemoryEvent(id, candidate.scope(), candidate.visibility(), provenance,
                        Instant.ofEpochMilli(row.getLong("source_timestamp_epoch_millis")),
                        Instant.ofEpochMilli(row.getLong("recorded_at_epoch_millis")), candidate.confidence(), candidate.importance(), candidate.content());
                }
            }
        }
        UUID id = UUID.randomUUID();
        try (PreparedStatement insert = connection.prepareStatement(
            "INSERT INTO memory_events(event_id, content, scope_type, scope_player_uuid, visibility, first_sequence, last_sequence, "
                + "source_timestamp_epoch_millis, recorded_at_epoch_millis, confidence, importance) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        )) {
            insert.setString(1, id.toString());
            insert.setString(2, candidate.content());
            insert.setString(3, scopeType(candidate.scope()));
            setOptionalString(insert, 4, scopePlayerId(candidate.scope()));
            insert.setString(5, candidate.visibility().name());
            insert.setLong(6, provenance.sourceRange().firstSequence());
            insert.setLong(7, provenance.sourceRange().lastSequence());
            insert.setLong(8, sourceTimestamp.toEpochMilli());
            insert.setLong(9, recordedAt.toEpochMilli());
            insert.setDouble(10, candidate.confidence().value());
            insert.setDouble(11, candidate.importance().value());
            insert.executeUpdate();
        }
        insertDerivedSources("EVENT", id, provenance.sourceBatchIds());
        return new MemoryEvent(id, candidate.scope(), candidate.visibility(), provenance, sourceTimestamp, recordedAt,
            candidate.confidence(), candidate.importance(), candidate.content());
    }

    private void insertSummary(
        DerivedMemoryProvenance provenance,
        DerivedMemoryCandidate candidate,
        Instant sourceTimestamp,
        Instant recordedAt
    ) throws SQLException {
        UUID seriesId = findSummarySeries(provenance.sourceRange(), candidate.scope(), candidate.visibility()).orElseGet(UUID::randomUUID);
        int version = nextVersion("memory_summary_versions", "summary_series_id", seriesId);
        UUID versionId = UUID.randomUUID();
        try (PreparedStatement insert = connection.prepareStatement(
            "INSERT INTO memory_summary_versions(summary_version_id, summary_series_id, version_number, content, scope_type, scope_player_uuid, visibility, "
                + "first_sequence, last_sequence, source_timestamp_epoch_millis, recorded_at_epoch_millis, confidence, importance) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        )) {
            insert.setString(1, versionId.toString()); insert.setString(2, seriesId.toString()); insert.setInt(3, version);
            insert.setString(4, candidate.content()); insert.setString(5, scopeType(candidate.scope()));
            setOptionalString(insert, 6, scopePlayerId(candidate.scope())); insert.setString(7, candidate.visibility().name());
            insert.setLong(8, provenance.sourceRange().firstSequence()); insert.setLong(9, provenance.sourceRange().lastSequence());
            insert.setLong(10, sourceTimestamp.toEpochMilli()); insert.setLong(11, recordedAt.toEpochMilli());
            insert.setDouble(12, candidate.confidence().value()); insert.setDouble(13, candidate.importance().value()); insert.executeUpdate();
        }
        insertDerivedSources("SUMMARY", versionId, provenance.sourceBatchIds());
    }

    private void insertCurrentSituation(
        DerivedMemoryProvenance provenance,
        DerivedMemoryCandidate candidate,
        Instant sourceTimestamp,
        Instant recordedAt
    ) throws SQLException {
        UUID seriesId = findCurrentSituationSeries(candidate.scope(), candidate.visibility()).orElseGet(UUID::randomUUID);
        int version = nextVersion("memory_current_situation_versions", "situation_series_id", seriesId);
        UUID versionId = UUID.randomUUID();
        try (PreparedStatement insert = connection.prepareStatement(
            "INSERT INTO memory_current_situation_versions(situation_version_id, situation_series_id, version_number, content, scope_type, scope_player_uuid, visibility, "
                + "first_sequence, last_sequence, source_timestamp_epoch_millis, recorded_at_epoch_millis, confidence, importance) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        )) {
            insert.setString(1, versionId.toString()); insert.setString(2, seriesId.toString()); insert.setInt(3, version);
            insert.setString(4, candidate.content()); insert.setString(5, scopeType(candidate.scope()));
            setOptionalString(insert, 6, scopePlayerId(candidate.scope())); insert.setString(7, candidate.visibility().name());
            insert.setLong(8, provenance.sourceRange().firstSequence()); insert.setLong(9, provenance.sourceRange().lastSequence());
            insert.setLong(10, sourceTimestamp.toEpochMilli()); insert.setLong(11, recordedAt.toEpochMilli());
            insert.setDouble(12, candidate.confidence().value()); insert.setDouble(13, candidate.importance().value()); insert.executeUpdate();
        }
        insertDerivedSources("CURRENT_SITUATION", versionId, provenance.sourceBatchIds());
    }

    private Optional<UUID> findSummarySeries(JournalSequenceRange range, MemoryScope scope, MemoryVisibility visibility) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT summary_series_id FROM memory_summary_versions WHERE first_sequence = ? AND last_sequence = ? "
                + "AND scope_type = ? AND COALESCE(scope_player_uuid, '') = COALESCE(?, '') AND visibility = ? "
                + "ORDER BY version_number DESC LIMIT 1"
        )) {
            statement.setLong(1, range.firstSequence()); statement.setLong(2, range.lastSequence()); statement.setString(3, scopeType(scope));
            setOptionalString(statement, 4, scopePlayerId(scope)); statement.setString(5, visibility.name());
            try (ResultSet row = statement.executeQuery()) { return row.next() ? Optional.of(UUID.fromString(row.getString(1))) : Optional.empty(); }
        }
    }

    private Optional<UUID> findCurrentSituationSeries(MemoryScope scope, MemoryVisibility visibility) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT situation_series_id FROM memory_current_situation_versions WHERE scope_type = ? "
                + "AND COALESCE(scope_player_uuid, '') = COALESCE(?, '') AND visibility = ? ORDER BY version_number DESC LIMIT 1"
        )) {
            statement.setString(1, scopeType(scope)); setOptionalString(statement, 2, scopePlayerId(scope)); statement.setString(3, visibility.name());
            try (ResultSet row = statement.executeQuery()) { return row.next() ? Optional.of(UUID.fromString(row.getString(1))) : Optional.empty(); }
        }
    }

    private int nextVersion(String table, String seriesColumn, UUID seriesId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COALESCE(MAX(version_number), 0) + 1 FROM " + table + " WHERE " + seriesColumn + " = ?")) {
            statement.setString(1, seriesId.toString());
            try (ResultSet row = statement.executeQuery()) { row.next(); return row.getInt(1); }
        }
    }

    private void insertDerivedSources(String kind, UUID recordId, List<UUID> batchIds) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
            "INSERT INTO memory_derived_sources(record_kind, record_id, batch_id, ordinal) VALUES (?, ?, ?, ?)"
        )) {
            for (int ordinal = 0; ordinal < batchIds.size(); ordinal++) {
                insert.setString(1, kind); insert.setString(2, recordId.toString()); insert.setString(3, batchIds.get(ordinal).toString()); insert.setInt(4, ordinal);
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private MemoryCompactionSnapshot loadCompactionSnapshot() throws SQLException {
        List<MemoryEvent> events = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM memory_events ORDER BY recorded_at_epoch_millis, event_id"); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) events.add(memoryEvent(rows));
        }
        List<MemoryChunkSummary> summaries = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM memory_summary_versions ORDER BY recorded_at_epoch_millis, summary_series_id, version_number"); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) summaries.add(summary(rows));
        }
        List<CurrentSituationVersion> situations = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM memory_current_situation_versions ORDER BY recorded_at_epoch_millis, situation_series_id, version_number"); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) situations.add(currentSituation(rows));
        }
        return new MemoryCompactionSnapshot(worldIdentity, events, summaries, situations);
    }

    private MemoryEvent memoryEvent(ResultSet row) throws SQLException {
        UUID id = UUID.fromString(row.getString("event_id"));
        return new MemoryEvent(id, scope(row), MemoryVisibility.valueOf(row.getString("visibility")),
            derivedProvenance("EVENT", id, range(row)), Instant.ofEpochMilli(row.getLong("source_timestamp_epoch_millis")),
            Instant.ofEpochMilli(row.getLong("recorded_at_epoch_millis")), new MemoryConfidence(row.getDouble("confidence")),
            new MemoryImportance(row.getDouble("importance")), row.getString("content"));
    }

    private MemoryChunkSummary summary(ResultSet row) throws SQLException {
        UUID versionId = UUID.fromString(row.getString("summary_version_id"));
        return new MemoryChunkSummary(UUID.fromString(row.getString("summary_series_id")), versionId, row.getInt("version_number"),
            scope(row), MemoryVisibility.valueOf(row.getString("visibility")), derivedProvenance("SUMMARY", versionId, range(row)),
            Instant.ofEpochMilli(row.getLong("source_timestamp_epoch_millis")), Instant.ofEpochMilli(row.getLong("recorded_at_epoch_millis")),
            new MemoryConfidence(row.getDouble("confidence")), new MemoryImportance(row.getDouble("importance")), row.getString("content"));
    }

    private CurrentSituationVersion currentSituation(ResultSet row) throws SQLException {
        UUID versionId = UUID.fromString(row.getString("situation_version_id"));
        return new CurrentSituationVersion(UUID.fromString(row.getString("situation_series_id")), versionId, row.getInt("version_number"),
            scope(row), MemoryVisibility.valueOf(row.getString("visibility")), derivedProvenance("CURRENT_SITUATION", versionId, range(row)),
            Instant.ofEpochMilli(row.getLong("source_timestamp_epoch_millis")), Instant.ofEpochMilli(row.getLong("recorded_at_epoch_millis")),
            new MemoryConfidence(row.getDouble("confidence")), new MemoryImportance(row.getDouble("importance")), row.getString("content"));
    }

    private DerivedMemoryProvenance derivedProvenance(String kind, UUID recordId, JournalSequenceRange range) throws SQLException {
        List<UUID> batches = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT batch_id FROM memory_derived_sources WHERE record_kind = ? AND record_id = ? ORDER BY ordinal"
        )) {
            statement.setString(1, kind); statement.setString(2, recordId.toString());
            try (ResultSet rows = statement.executeQuery()) { while (rows.next()) batches.add(UUID.fromString(rows.getString(1))); }
        }
        return new DerivedMemoryProvenance(range, batches);
    }

    private static JournalSequenceRange range(ResultSet row) throws SQLException {
        return new JournalSequenceRange(row.getLong("first_sequence"), row.getLong("last_sequence"));
    }

    private static MemoryScope scope(ResultSet row) throws SQLException {
        return "WORLD".equals(row.getString("scope_type")) ? MemoryScope.world()
            : MemoryScope.player(UUID.fromString(row.getString("scope_player_uuid")));
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
        rebuildSearchDocuments(connection);
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
            statement.execute("CREATE TABLE IF NOT EXISTS memory_events (event_id TEXT PRIMARY KEY, content TEXT NOT NULL CHECK(length(trim(content)) > 0), scope_type TEXT NOT NULL CHECK(scope_type IN ('WORLD', 'PLAYER')), scope_player_uuid TEXT, visibility TEXT NOT NULL CHECK(visibility IN ('PUBLIC', 'PRIVATE')), first_sequence INTEGER NOT NULL, last_sequence INTEGER NOT NULL, source_timestamp_epoch_millis INTEGER NOT NULL, recorded_at_epoch_millis INTEGER NOT NULL, confidence REAL NOT NULL CHECK(confidence >= 0.0 AND confidence <= 1.0), importance REAL NOT NULL CHECK(importance >= 0.0 AND importance <= 1.0), CHECK((scope_type = 'WORLD' AND scope_player_uuid IS NULL) OR (scope_type = 'PLAYER' AND scope_player_uuid IS NOT NULL)), CHECK(last_sequence >= first_sequence))");
            statement.execute("CREATE TABLE IF NOT EXISTS memory_summary_versions (summary_version_id TEXT PRIMARY KEY, summary_series_id TEXT NOT NULL, version_number INTEGER NOT NULL CHECK(version_number > 0), content TEXT NOT NULL CHECK(length(trim(content)) > 0), scope_type TEXT NOT NULL CHECK(scope_type IN ('WORLD', 'PLAYER')), scope_player_uuid TEXT, visibility TEXT NOT NULL CHECK(visibility IN ('PUBLIC', 'PRIVATE')), first_sequence INTEGER NOT NULL, last_sequence INTEGER NOT NULL, source_timestamp_epoch_millis INTEGER NOT NULL, recorded_at_epoch_millis INTEGER NOT NULL, confidence REAL NOT NULL CHECK(confidence >= 0.0 AND confidence <= 1.0), importance REAL NOT NULL CHECK(importance >= 0.0 AND importance <= 1.0), UNIQUE(summary_series_id, version_number), CHECK((scope_type = 'WORLD' AND scope_player_uuid IS NULL) OR (scope_type = 'PLAYER' AND scope_player_uuid IS NOT NULL)), CHECK(last_sequence >= first_sequence))");
            statement.execute("CREATE TABLE IF NOT EXISTS memory_current_situation_versions (situation_version_id TEXT PRIMARY KEY, situation_series_id TEXT NOT NULL, version_number INTEGER NOT NULL CHECK(version_number > 0), content TEXT NOT NULL CHECK(length(trim(content)) > 0), scope_type TEXT NOT NULL CHECK(scope_type IN ('WORLD', 'PLAYER')), scope_player_uuid TEXT, visibility TEXT NOT NULL CHECK(visibility IN ('PUBLIC', 'PRIVATE')), first_sequence INTEGER NOT NULL, last_sequence INTEGER NOT NULL, source_timestamp_epoch_millis INTEGER NOT NULL, recorded_at_epoch_millis INTEGER NOT NULL, confidence REAL NOT NULL CHECK(confidence >= 0.0 AND confidence <= 1.0), importance REAL NOT NULL CHECK(importance >= 0.0 AND importance <= 1.0), UNIQUE(situation_series_id, version_number), CHECK((scope_type = 'WORLD' AND scope_player_uuid IS NULL) OR (scope_type = 'PLAYER' AND scope_player_uuid IS NOT NULL)), CHECK(last_sequence >= first_sequence))");
            statement.execute("CREATE TABLE IF NOT EXISTS memory_derived_sources (record_kind TEXT NOT NULL CHECK(record_kind IN ('EVENT', 'SUMMARY', 'CURRENT_SITUATION')), record_id TEXT NOT NULL, batch_id TEXT NOT NULL REFERENCES journal_batches(batch_id), ordinal INTEGER NOT NULL, PRIMARY KEY(record_kind, record_id, batch_id), UNIQUE(record_kind, record_id, ordinal))");
            statement.execute("CREATE TABLE IF NOT EXISTS memory_compaction_coverage (first_sequence INTEGER NOT NULL, last_sequence INTEGER NOT NULL, recorded_at_epoch_millis INTEGER NOT NULL, PRIMARY KEY(first_sequence, last_sequence), CHECK(last_sequence >= first_sequence))");
            statement.execute("CREATE INDEX IF NOT EXISTS memory_events_source_range_idx ON memory_events(first_sequence, last_sequence)");
            statement.execute("CREATE INDEX IF NOT EXISTS memory_summary_versions_source_range_idx ON memory_summary_versions(first_sequence, last_sequence)");
            statement.execute("CREATE INDEX IF NOT EXISTS memory_situations_scope_idx ON memory_current_situation_versions(scope_type, scope_player_uuid, visibility, version_number)");
            statement.execute("CREATE INDEX IF NOT EXISTS memory_coverage_range_idx ON memory_compaction_coverage(first_sequence, last_sequence)");
            statement.execute("CREATE TABLE IF NOT EXISTS memory_search_documents (document_id INTEGER PRIMARY KEY AUTOINCREMENT, record_type TEXT NOT NULL CHECK(record_type IN ('DIALOGUE', 'FACT', 'RELATIONSHIP', 'EVENT', 'SUMMARY')), stable_identity TEXT NOT NULL UNIQUE, content TEXT NOT NULL CHECK(length(trim(content)) > 0), scope_type TEXT NOT NULL CHECK(scope_type IN ('WORLD', 'PLAYER')), scope_player_uuid TEXT, visibility TEXT NOT NULL CHECK(visibility IN ('PUBLIC', 'PRIVATE')), first_sequence INTEGER NOT NULL, last_sequence INTEGER NOT NULL, source_timestamp_epoch_millis INTEGER NOT NULL, recorded_at_epoch_millis INTEGER NOT NULL, confidence REAL NOT NULL CHECK(confidence >= 0.0 AND confidence <= 1.0), importance REAL NOT NULL CHECK(importance >= 0.0 AND importance <= 1.0), source_batch_ids TEXT NOT NULL CHECK(length(trim(source_batch_ids)) > 0), CHECK((scope_type = 'WORLD' AND scope_player_uuid IS NULL) OR (scope_type = 'PLAYER' AND scope_player_uuid IS NOT NULL)), CHECK(last_sequence >= first_sequence))");
            statement.execute("CREATE VIRTUAL TABLE IF NOT EXISTS memory_search_fts USING fts5(content, content='memory_search_documents', content_rowid='document_id')");
            statement.execute("CREATE INDEX IF NOT EXISTS memory_search_documents_scope_idx ON memory_search_documents(visibility, scope_type, scope_player_uuid, last_sequence)");
        }
    }

    /** Deterministically rebuilds the local public-only canonical documents and FTS5 index from v1 source tables. */
    private static void rebuildSearchDocuments(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM memory_search_documents");
            statement.executeUpdate("INSERT INTO memory_search_documents(record_type, stable_identity, content, scope_type, scope_player_uuid, visibility, first_sequence, last_sequence, source_timestamp_epoch_millis, recorded_at_epoch_millis, confidence, importance, source_batch_ids) "
                + "SELECT 'DIALOGUE', 'dialogue:' || j.sequence, j.message_text, 'WORLD', NULL, 'PUBLIC', j.sequence, j.sequence, j.captured_at_epoch_millis, j.captured_at_epoch_millis, 1.0, 0.2, bm.batch_id FROM journal_messages j JOIN journal_batch_messages bm ON bm.message_sequence = j.sequence WHERE j.visibility = 'PUBLIC'");
            statement.executeUpdate("INSERT INTO memory_search_documents(record_type, stable_identity, content, scope_type, scope_player_uuid, visibility, first_sequence, last_sequence, source_timestamp_epoch_millis, recorded_at_epoch_millis, confidence, importance, source_batch_ids) "
                + "SELECT r.record_type, lower(r.record_type) || ':' || r.record_id, r.content, r.scope_type, r.scope_player_uuid, r.visibility, r.first_sequence, r.last_sequence, r.source_timestamp_epoch_millis, r.recorded_at_epoch_millis, r.confidence, r.importance, r.source_batch_id FROM memory_records r JOIN memory_confirmations c ON c.record_id = r.record_id WHERE r.record_state = 'CONFIRMED' AND r.visibility = 'PUBLIC'");
            statement.executeUpdate("INSERT INTO memory_search_documents(record_type, stable_identity, content, scope_type, scope_player_uuid, visibility, first_sequence, last_sequence, source_timestamp_epoch_millis, recorded_at_epoch_millis, confidence, importance, source_batch_ids) "
                + "SELECT 'EVENT', 'event:' || e.event_id, e.content, e.scope_type, e.scope_player_uuid, e.visibility, e.first_sequence, e.last_sequence, e.source_timestamp_epoch_millis, e.recorded_at_epoch_millis, e.confidence, e.importance, (SELECT group_concat(batch_id, ',') FROM (SELECT batch_id FROM memory_derived_sources ds WHERE ds.record_kind = 'EVENT' AND ds.record_id = e.event_id ORDER BY ordinal)) FROM memory_events e WHERE e.visibility = 'PUBLIC'");
            statement.executeUpdate("INSERT INTO memory_search_documents(record_type, stable_identity, content, scope_type, scope_player_uuid, visibility, first_sequence, last_sequence, source_timestamp_epoch_millis, recorded_at_epoch_millis, confidence, importance, source_batch_ids) "
                + "SELECT 'SUMMARY', 'summary:' || s.summary_version_id, s.content, s.scope_type, s.scope_player_uuid, s.visibility, s.first_sequence, s.last_sequence, s.source_timestamp_epoch_millis, s.recorded_at_epoch_millis, s.confidence, s.importance, (SELECT group_concat(batch_id, ',') FROM (SELECT batch_id FROM memory_derived_sources ds WHERE ds.record_kind = 'SUMMARY' AND ds.record_id = s.summary_version_id ORDER BY ordinal)) FROM memory_summary_versions s WHERE s.visibility = 'PUBLIC'");
            statement.execute("INSERT INTO memory_search_fts(memory_search_fts) VALUES('rebuild')");
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
