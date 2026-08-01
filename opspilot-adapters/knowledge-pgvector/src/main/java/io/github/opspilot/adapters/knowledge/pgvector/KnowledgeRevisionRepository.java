package io.github.opspilot.adapters.knowledge.pgvector;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Atomic collection-level knowledge revision activation, rollback, snapshot and cleanup primitives. */
public final class KnowledgeRevisionRepository {
    private final DataSource dataSource;

    public KnowledgeRevisionRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public void activate(UUID collectionId, UUID targetRevisionId) {
        activate(collectionId, targetRevisionId, point -> { });
    }

    public void markReady(UUID revisionId, int expectedChunks, int completedChunks) {
        if (expectedChunks < 0 || completedChunks != expectedChunks) {
            throw new RevisionException("KNOWLEDGE_REVISION_GATE_FAILED");
        }
        String sql = """
                UPDATE opspilot.knowledge_revision
                SET status = 'READY', coverage_status = 'COMPLETE',
                    expected_chunk_count = ?, completed_chunk_count = ?
                WHERE knowledge_revision_id = ? AND status = 'BUILDING'
                """;
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setInt(1, expectedChunks);
            statement.setInt(2, completedChunks);
            statement.setObject(3, revisionId);
            if (statement.executeUpdate() != 1) throw new RevisionException("KNOWLEDGE_REVISION_WRITE_CONFLICT");
        } catch (SQLException failure) {
            throw new RevisionException("KNOWLEDGE_REVISION_WRITE_FAILED", failure);
        }
    }

    public void activate(UUID collectionId, UUID targetRevisionId, ActivationFailure injection) {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                ActivationTarget target = lockTarget(connection, collectionId, targetRevisionId);
                if (!("READY".equals(target.status()) || "RETAINED".equals(target.status()))
                        || !"COMPLETE".equals(target.coverage())
                        || target.completed() != target.expected()) {
                    throw new RevisionException("KNOWLEDGE_REVISION_GATE_FAILED");
                }
                updateAny(connection, """
                        UPDATE opspilot.knowledge_revision
                        SET status = 'RETAINED', searchable = false
                        WHERE collection_id = ? AND status = 'ACTIVE' AND knowledge_revision_id <> ?
                        """, collectionId, targetRevisionId);
                injection.at(ActivationPoint.AFTER_OLD_REVISION_DISABLED);
                update(connection, """
                        UPDATE opspilot.knowledge_revision
                        SET status = 'ACTIVE', searchable = true, activated_at = now()
                        WHERE knowledge_revision_id = ?
                        """, targetRevisionId);
                update(connection, """
                        UPDATE opspilot.knowledge_collection
                        SET active_knowledge_revision_id = ?, active_model_revision_id = ?
                        WHERE collection_id = ?
                        """, targetRevisionId, target.modelRevisionId(), collectionId);
                injection.at(ActivationPoint.BEFORE_COMMIT);
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException failure) {
            throw new RevisionException("KNOWLEDGE_REVISION_ACTIVATION_FAILED", failure);
        }
    }

    public void rollback(
            UUID collectionId, UUID retainedRevisionId,
            boolean authorized, boolean referencesIntact) {
        if (!authorized) throw new RevisionException("KNOWLEDGE_ROLLBACK_FORBIDDEN");
        if (!referencesIntact) throw new RevisionException("KNOWLEDGE_ROLLBACK_INTEGRITY_FAILED");
        activate(collectionId, retainedRevisionId);
    }

    public EffectiveSnapshot resolveRunSnapshot(UUID runId, UUID collectionId) {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                EffectiveSnapshot existing = loadRunSnapshot(connection, runId);
                if (existing != null) {
                    connection.commit();
                    return existing;
                }
                EffectiveSnapshot active = loadActiveSnapshot(connection, collectionId);
                update(connection, """
                        UPDATE opspilot.incident_run SET effective_knowledge_revision_id = ?
                        WHERE run_id = ? AND effective_knowledge_revision_id IS NULL
                        """, active.knowledgeRevisionId(), runId);
                connection.commit();
                return active;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException failure) {
            throw new RevisionException("KNOWLEDGE_SNAPSHOT_RESOLUTION_FAILED", failure);
        }
    }

    /** Reads the Supervisor-frozen revision without allowing a professional Agent to mutate Run authority. */
    public EffectiveSnapshot requireRunSnapshot(UUID runId) {
        try (var connection = dataSource.getConnection()) {
            EffectiveSnapshot snapshot = loadRunSnapshot(connection, runId);
            if (snapshot == null) throw new RevisionException("KNOWLEDGE_RUN_SNAPSHOT_MISSING");
            return snapshot;
        } catch (SQLException failure) {
            throw new RevisionException("KNOWLEDGE_SNAPSHOT_READ_FAILED", failure);
        }
    }

    public List<UUID> cleanupCandidates(Instant before) {
        String sql = """
                SELECT revision.knowledge_revision_id
                FROM opspilot.knowledge_revision revision
                WHERE revision.status IN ('BUILDING','FAILED','RETAINED')
                  AND NOT revision.searchable
                  AND coalesce(revision.retain_until, revision.created_at) < ?
                  AND NOT EXISTS (SELECT 1 FROM opspilot.incident_run run
                                  WHERE run.effective_knowledge_revision_id = revision.knowledge_revision_id)
                  AND NOT EXISTS (SELECT 1 FROM opspilot.knowledge_reference reference
                                  WHERE reference.knowledge_revision_id = revision.knowledge_revision_id)
                ORDER BY revision.knowledge_revision_id
                """;
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.from(before));
            List<UUID> ids = new ArrayList<>();
            try (var result = statement.executeQuery()) {
                while (result.next()) ids.add(result.getObject(1, UUID.class));
            }
            return List.copyOf(ids);
        } catch (SQLException failure) {
            throw new RevisionException("KNOWLEDGE_CLEANUP_SCAN_FAILED", failure);
        }
    }

    public boolean markCleaned(UUID revisionId) {
        String sql = """
                UPDATE opspilot.knowledge_revision AS revision SET status = 'DELETED', searchable = false
                WHERE revision.knowledge_revision_id = ? AND revision.status <> 'ACTIVE' AND NOT revision.searchable
                  AND NOT EXISTS (SELECT 1 FROM opspilot.incident_run run
                                  WHERE run.effective_knowledge_revision_id = revision.knowledge_revision_id)
                  AND NOT EXISTS (SELECT 1 FROM opspilot.knowledge_reference reference
                                  WHERE reference.knowledge_revision_id = revision.knowledge_revision_id)
                """;
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, revisionId);
            return statement.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new RevisionException("KNOWLEDGE_CLEANUP_FAILED", failure);
        }
    }

    private static ActivationTarget lockTarget(
            Connection connection, UUID collectionId, UUID targetId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT revision.status, revision.coverage_status, revision.expected_chunk_count,
                       revision.completed_chunk_count, revision.model_revision_id
                FROM opspilot.knowledge_collection collection
                JOIN opspilot.knowledge_revision revision ON revision.collection_id = collection.collection_id
                WHERE collection.collection_id = ? AND revision.knowledge_revision_id = ?
                FOR UPDATE OF collection, revision
                """)) {
            statement.setObject(1, collectionId);
            statement.setObject(2, targetId);
            try (var result = statement.executeQuery()) {
                if (!result.next()) throw new RevisionException("KNOWLEDGE_REVISION_NOT_FOUND");
                return new ActivationTarget(result.getString(1), result.getString(2), result.getInt(3),
                        result.getInt(4), result.getObject(5, UUID.class));
            }
        }
    }

    private static EffectiveSnapshot loadRunSnapshot(Connection connection, UUID runId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT revision.knowledge_revision_id, revision.model_revision_id,
                       revision.embedding_dimension
                FROM opspilot.incident_run run
                JOIN opspilot.knowledge_revision revision
                  ON revision.knowledge_revision_id = run.effective_knowledge_revision_id
                WHERE run.run_id = ?
                """)) {
            statement.setObject(1, runId);
            try (var result = statement.executeQuery()) {
                return result.next() ? new EffectiveSnapshot(result.getObject(1, UUID.class),
                        result.getObject(2, UUID.class), result.getInt(3)) : null;
            }
        }
    }

    private static EffectiveSnapshot loadActiveSnapshot(Connection connection, UUID collectionId)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT revision.knowledge_revision_id, revision.model_revision_id,
                       revision.embedding_dimension
                FROM opspilot.knowledge_collection collection
                JOIN opspilot.knowledge_revision revision
                  ON revision.knowledge_revision_id = collection.active_knowledge_revision_id
                WHERE collection.collection_id = ? AND revision.status = 'ACTIVE' AND revision.searchable
                """)) {
            statement.setObject(1, collectionId);
            try (var result = statement.executeQuery()) {
                if (!result.next()) throw new RevisionException("KNOWLEDGE_ACTIVE_REVISION_NOT_FOUND");
                return new EffectiveSnapshot(result.getObject(1, UUID.class),
                        result.getObject(2, UUID.class), result.getInt(3));
            }
        }
    }

    private static void update(Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            if (statement.executeUpdate() != 1) throw new RevisionException("KNOWLEDGE_REVISION_WRITE_CONFLICT");
        }
    }

    private static void updateAny(Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            statement.executeUpdate();
        }
    }

    private record ActivationTarget(
            String status, String coverage, int expected, int completed, UUID modelRevisionId) { }
    public record EffectiveSnapshot(UUID knowledgeRevisionId, UUID modelRevisionId, int dimension) { }
    public enum ActivationPoint { AFTER_OLD_REVISION_DISABLED, BEFORE_COMMIT }
    @FunctionalInterface public interface ActivationFailure { void at(ActivationPoint point); }
    public static final class RevisionException extends RuntimeException {
        public RevisionException(String code) { super(code); }
        public RevisionException(String code, Throwable cause) { super(code, cause); }
    }
}
