package io.github.opspilot.adapters.knowledge.pgvector;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Persistence-only knowledge document/version/chunk and ingestion checkpoint primitives. */
public final class KnowledgeVersionRepository {
    private final DataSource dataSource;

    public KnowledgeVersionRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public void createDocument(UUID documentId, UUID collectionId, String externalKey) {
        execute("""
                INSERT INTO opspilot.knowledge_document
                    (document_id, collection_id, external_key, status)
                VALUES (?, ?, ?, 'INACTIVE')
                """, documentId, collectionId, externalKey);
    }

    public void createVersion(
            UUID versionId, UUID documentId, int versionNumber, String contentSha256,
            UUID sourceArtifactId, int expectedChunks, Instant retainUntil) {
        execute("""
                INSERT INTO opspilot.knowledge_document_version
                    (document_version_id, document_id, version_number, status, content_sha256,
                     source_artifact_id, coverage_status, expected_chunk_count, retain_until)
                VALUES (?, ?, ?, 'BUILDING', ?, ?, 'PENDING', ?, ?)
                """, versionId, documentId, versionNumber, contentSha256, sourceArtifactId,
                expectedChunks, retainUntil);
    }

    public void appendChunk(
            UUID chunkId, UUID versionId, int ordinal, String contentSha256,
            UUID contentArtifactId, String metadataJson) {
        execute("""
                INSERT INTO opspilot.knowledge_chunk
                    (chunk_id, document_version_id, ordinal, content_sha256,
                     content_artifact_id, metadata_json)
                VALUES (?, ?, ?, ?, ?, ?::jsonb)
                """, chunkId, versionId, ordinal, contentSha256, contentArtifactId, metadataJson);
    }

    public void createJob(UUID jobId, UUID versionId, UUID modelRevisionId) {
        execute("""
                INSERT INTO opspilot.knowledge_ingestion_job
                    (job_id, document_version_id, model_revision_id, status)
                VALUES (?, ?, ?, 'PENDING')
                """, jobId, versionId, modelRevisionId);
    }

    public void recordCheckpoint(
            UUID jobId,
            int checkpointOrdinal,
            int completedChunks,
            String coverageStatus,
            String lastError) {
        String jobStatus = "COMPLETE".equals(coverageStatus) ? "COMPLETED"
                : lastError == null ? "RUNNING" : "RECOVERING";
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var job = connection.prepareStatement("""
                    UPDATE opspilot.knowledge_ingestion_job
                    SET checkpoint_ordinal = ?, attempt_count = attempt_count + 1,
                        status = ?, last_error = ?, updated_at = now()
                    WHERE job_id = ?
                    """);
                 var version = connection.prepareStatement("""
                    UPDATE opspilot.knowledge_document_version version
                    SET completed_chunk_count = ?, coverage_status = ?
                    FROM opspilot.knowledge_ingestion_job job
                    WHERE job.job_id = ? AND job.document_version_id = version.document_version_id
                    """)) {
                job.setInt(1, checkpointOrdinal);
                job.setString(2, jobStatus);
                job.setString(3, lastError);
                job.setObject(4, jobId);
                if (job.executeUpdate() != 1) {
                    throw new KnowledgePersistenceException("KNOWLEDGE_JOB_NOT_FOUND");
                }
                version.setInt(1, completedChunks);
                version.setString(2, coverageStatus);
                version.setObject(3, jobId);
                if (version.executeUpdate() != 1) {
                    throw new KnowledgePersistenceException("KNOWLEDGE_VERSION_NOT_FOUND");
                }
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException exception) {
            throw new KnowledgePersistenceException("KNOWLEDGE_CHECKPOINT_FAILED", exception);
        }
    }

    public void recordChunkFailure(
            UUID jobId, UUID chunkId, int attempt, String errorCode, String summary) {
        execute("""
                INSERT INTO opspilot.knowledge_ingestion_failure
                    (job_id, chunk_id, attempt, error_code, error_summary)
                VALUES (?, ?, ?, ?, ?)
                """, jobId, chunkId, attempt, errorCode, summary);
    }

    public Optional<JobCheckpoint> loadJob(UUID jobId) {
        String sql = """
                SELECT job.document_version_id, job.model_revision_id, job.status,
                       job.attempt_count, job.checkpoint_ordinal, job.last_error,
                       version.coverage_status, version.expected_chunk_count,
                       version.completed_chunk_count,
                       (SELECT count(*) FROM opspilot.knowledge_ingestion_failure failure
                        WHERE failure.job_id = job.job_id) AS failure_count
                FROM opspilot.knowledge_ingestion_job job
                JOIN opspilot.knowledge_document_version version
                  ON version.document_version_id = job.document_version_id
                WHERE job.job_id = ?
                """;
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, jobId);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(new JobCheckpoint(
                        jobId, result.getObject(1, UUID.class), result.getObject(2, UUID.class),
                        result.getString(3), result.getInt(4), result.getInt(5), result.getString(6),
                        result.getString(7), result.getInt(8), result.getInt(9), result.getInt(10)));
            }
        } catch (SQLException exception) {
            throw new KnowledgePersistenceException("KNOWLEDGE_JOB_LOAD_FAILED", exception);
        }
    }

    public void reuseEmbedding(
            UUID sourceChunkId,
            UUID targetChunkId,
            UUID sourceRevisionId,
            UUID targetRevisionId) {
        if (!sourceRevisionId.equals(targetRevisionId)) {
            throw new KnowledgePersistenceException("CROSS_REVISION_VECTOR_REUSE_FORBIDDEN");
        }
        String sql = """
                INSERT INTO opspilot.knowledge_embedding
                    (chunk_id, model_revision_id, embedding_dimension, embedding, content_sha256)
                SELECT target.chunk_id, embedding.model_revision_id, embedding.embedding_dimension,
                       embedding.embedding, target.content_sha256
                FROM opspilot.knowledge_chunk source
                JOIN opspilot.knowledge_embedding embedding ON embedding.chunk_id = source.chunk_id
                JOIN opspilot.knowledge_chunk target ON target.chunk_id = ?
                WHERE source.chunk_id = ?
                  AND embedding.model_revision_id = ?
                  AND source.content_sha256 = target.content_sha256
                """;
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, targetChunkId);
            statement.setObject(2, sourceChunkId);
            statement.setObject(3, sourceRevisionId);
            if (statement.executeUpdate() != 1) {
                throw new KnowledgePersistenceException("VECTOR_REUSE_HASH_OR_REVISION_MISMATCH");
            }
        } catch (SQLException exception) {
            throw new KnowledgePersistenceException("VECTOR_REUSE_FAILED", exception);
        }
    }

    public void activateVersion(UUID versionId, UUID modelRevisionId) {
        activateVersion(versionId, modelRevisionId, point -> { });
    }

    public void activateVersion(UUID versionId, UUID modelRevisionId, ActivationFailure failure) {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                ActivationTarget target = lockActivationTarget(connection, versionId);
                if (!"COMPLETE".equals(target.coverageStatus())
                        || target.completedChunks() != target.expectedChunks()) {
                    throw new KnowledgePersistenceException("KNOWLEDGE_COVERAGE_INCOMPLETE");
                }
                update(connection, """
                        UPDATE opspilot.knowledge_document_version
                        SET status = 'RETAINED'
                        WHERE document_id = ? AND status = 'ACTIVE' AND document_version_id <> ?
                        """, target.documentId(), versionId);
                update(connection, """
                        UPDATE opspilot.knowledge_chunk SET searchable = false
                        WHERE document_version_id IN (
                            SELECT document_version_id FROM opspilot.knowledge_document_version
                            WHERE document_id = ? AND document_version_id <> ?)
                        """, target.documentId(), versionId);
                failure.at(ActivationPoint.AFTER_OLD_REVISION_DISABLED);
                update(connection,
                        "UPDATE opspilot.knowledge_document_version SET status = 'ACTIVE' WHERE document_version_id = ?",
                        versionId);
                update(connection,
                        "UPDATE opspilot.knowledge_chunk SET searchable = true WHERE document_version_id = ? AND deleted_at IS NULL",
                        versionId);
                update(connection, """
                        UPDATE opspilot.knowledge_document
                        SET active_version_id = ?, status = 'ACTIVE'
                        WHERE document_id = ?
                        """, versionId, target.documentId());
                update(connection, """
                        UPDATE opspilot.knowledge_collection SET active_model_revision_id = ?
                        WHERE collection_id = ?
                        """, modelRevisionId, target.collectionId());
                failure.at(ActivationPoint.BEFORE_COMMIT);
                connection.commit();
            } catch (SQLException | RuntimeException problem) {
                connection.rollback();
                throw problem;
            }
        } catch (SQLException exception) {
            throw new KnowledgePersistenceException("KNOWLEDGE_ACTIVATION_FAILED", exception);
        }
    }

    public void markDeleted(UUID documentId, Instant deletedAt) {
        execute("""
                UPDATE opspilot.knowledge_document
                SET status = 'DELETED', deleted_at = ?
                WHERE document_id = ?
                """, deletedAt, documentId);
    }

    private static ActivationTarget lockActivationTarget(Connection connection, UUID versionId)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT version.document_id, document.collection_id, version.coverage_status,
                       version.expected_chunk_count, version.completed_chunk_count
                FROM opspilot.knowledge_document_version version
                JOIN opspilot.knowledge_document document ON document.document_id = version.document_id
                WHERE version.document_version_id = ?
                FOR UPDATE OF version, document
                """)) {
            statement.setObject(1, versionId);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new KnowledgePersistenceException("KNOWLEDGE_VERSION_NOT_FOUND");
                }
                return new ActivationTarget(
                        result.getObject(1, UUID.class), result.getObject(2, UUID.class),
                        result.getString(3), result.getInt(4), result.getInt(5));
            }
        }
    }

    private void execute(String sql, Object... values) {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            if (statement.executeUpdate() != 1) {
                throw new KnowledgePersistenceException("KNOWLEDGE_WRITE_CONFLICT");
            }
        } catch (SQLException exception) {
            throw new KnowledgePersistenceException("KNOWLEDGE_WRITE_FAILED", exception);
        }
    }

    private static void update(Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            statement.executeUpdate();
        }
    }

    private static void bind(java.sql.PreparedStatement statement, Object[] values) throws SQLException {
        for (int index = 0; index < values.length; index++) {
            if (values[index] instanceof Instant instant) {
                statement.setTimestamp(index + 1, Timestamp.from(instant));
            } else {
                statement.setObject(index + 1, values[index]);
            }
        }
    }

    private record ActivationTarget(
            UUID documentId, UUID collectionId, String coverageStatus,
            int expectedChunks, int completedChunks) { }

    public record JobCheckpoint(
            UUID jobId, UUID versionId, UUID modelRevisionId, String status,
            int attempts, int checkpointOrdinal, String lastError, String coverageStatus,
            int expectedChunks, int completedChunks, int failedChunks) { }

    public enum ActivationPoint { AFTER_OLD_REVISION_DISABLED, BEFORE_COMMIT }

    @FunctionalInterface
    public interface ActivationFailure {
        void at(ActivationPoint point);
    }

    public static final class KnowledgePersistenceException extends RuntimeException {
        public KnowledgePersistenceException(String code) { super(code); }
        public KnowledgePersistenceException(String code, Throwable cause) { super(code, cause); }
    }
}
