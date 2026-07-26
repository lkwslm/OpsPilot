package io.github.opspilot.adapters.knowledge.pgvector;

import io.github.opspilot.core.port.repository.EmbeddingBatchCommitPort;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Commits validated vectors, coverage, and the resumable job checkpoint in one transaction. */
public final class PostgresEmbeddingBatchCommitRepository implements EmbeddingBatchCommitPort {
    private final DataSource dataSource;

    public PostgresEmbeddingBatchCommitRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public void commit(EmbeddingBatchCommit command) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                JobIdentity job = lockJob(connection, command.jobId());
                if (!job.modelRevisionId().equals(command.modelRevisionId())
                        || job.expectedChunks() != command.expectedChunks()
                        || command.completedChunks() != job.completedChunks() + command.vectors().size()) {
                    throw new EmbeddingBatchPersistenceException("EMBEDDING_JOB_IDENTITY_MISMATCH");
                }
                for (EmbeddingVectorWrite vector : command.vectors()) {
                    insertVector(connection, command, vector);
                }
                String coverage = command.completedChunks() == command.expectedChunks() ? "COMPLETE" : "PARTIAL";
                String status = "COMPLETE".equals(coverage) ? "COMPLETED" : "RUNNING";
                updateJob(connection, command, status);
                updateCoverage(connection, command.jobId(), command.completedChunks(), coverage);
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException failure) {
            throw new EmbeddingBatchPersistenceException("EMBEDDING_BATCH_COMMIT_FAILED", failure);
        }
    }

    @Override
    public void recordFailure(EmbeddingBatchFailure failure) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                lockJob(connection, failure.jobId());
                try (var insert = connection.prepareStatement("""
                        INSERT INTO opspilot.knowledge_ingestion_failure
                            (job_id, chunk_id, attempt, error_code, error_summary)
                        VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (job_id, chunk_id, attempt) DO NOTHING
                        """)) {
                    for (UUID chunkId : failure.chunkIds()) {
                        insert.setObject(1, failure.jobId());
                        insert.setObject(2, chunkId);
                        insert.setInt(3, failure.attempt());
                        insert.setString(4, failure.errorCode());
                        insert.setString(5, "Embedding batch failed; source payload omitted");
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
                try (var update = connection.prepareStatement("""
                        UPDATE opspilot.knowledge_ingestion_job
                        SET status = ?, attempt_count = GREATEST(attempt_count, ?),
                            last_error = ?, updated_at = now()
                        WHERE job_id = ? AND checkpoint_ordinal = ?
                        """)) {
                    update.setString(1, failure.retryable() ? "RECOVERING" : "FAILED");
                    update.setInt(2, failure.attempt());
                    update.setString(3, failure.errorCode());
                    update.setObject(4, failure.jobId());
                    update.setInt(5, failure.checkpointOrdinal());
                    if (update.executeUpdate() != 1) {
                        throw new EmbeddingBatchPersistenceException("EMBEDDING_CHECKPOINT_CONFLICT");
                    }
                }
                connection.commit();
            } catch (SQLException | RuntimeException problem) {
                connection.rollback();
                throw problem;
            }
        } catch (SQLException problem) {
            throw new EmbeddingBatchPersistenceException("EMBEDDING_FAILURE_CHECKPOINT_FAILED", problem);
        }
    }

    @Override
    public Optional<EmbeddingJobProgress> load(UUID jobId) {
        String sql = """
                SELECT job.model_revision_id, job.checkpoint_ordinal,
                       version.completed_chunk_count, version.expected_chunk_count,
                       job.attempt_count, job.status, job.last_error
                FROM opspilot.knowledge_ingestion_job job
                JOIN opspilot.knowledge_document_version version
                  ON version.document_version_id = job.document_version_id
                WHERE job.job_id = ?
                """;
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, jobId);
            try (var result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                return Optional.of(new EmbeddingJobProgress(jobId, result.getObject(1, UUID.class),
                        result.getInt(2), result.getInt(3), result.getInt(4), result.getInt(5),
                        result.getString(6), result.getString(7)));
            }
        } catch (SQLException failure) {
            throw new EmbeddingBatchPersistenceException("EMBEDDING_JOB_LOAD_FAILED", failure);
        }
    }

    @Override
    public Optional<ReusableEmbedding> findReusable(UUID modelRevisionId, String contentSha256) {
        if (contentSha256 == null || !contentSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("contentSha256 must be lowercase SHA-256");
        }
        String sql = """
                SELECT chunk_id, embedding_dimension, embedding::text
                FROM opspilot.knowledge_embedding
                WHERE model_revision_id = ? AND content_sha256 = ?
                ORDER BY created_at, chunk_id
                LIMIT 1
                """;
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, modelRevisionId);
            statement.setString(2, contentSha256);
            try (var result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                int dimension = result.getInt(2);
                return Optional.of(new ReusableEmbedding(result.getObject(1, UUID.class), dimension,
                        parseVector(result.getString(3), dimension)));
            }
        } catch (SQLException failure) {
            throw new EmbeddingBatchPersistenceException("EMBEDDING_REUSE_LOOKUP_FAILED", failure);
        }
    }

    private static JobIdentity lockJob(Connection connection, UUID jobId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT job.model_revision_id, version.expected_chunk_count, version.completed_chunk_count
                FROM opspilot.knowledge_ingestion_job job
                JOIN opspilot.knowledge_document_version version
                  ON version.document_version_id = job.document_version_id
                WHERE job.job_id = ?
                FOR UPDATE OF job, version
                """)) {
            statement.setObject(1, jobId);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new EmbeddingBatchPersistenceException("EMBEDDING_JOB_NOT_FOUND");
                }
                return new JobIdentity(result.getObject(1, UUID.class), result.getInt(2), result.getInt(3));
            }
        }
    }

    private static void insertVector(
            Connection connection, EmbeddingBatchCommit command, EmbeddingVectorWrite vector) throws SQLException {
        validateVector(vector.vector(), command.dimension(), command.distanceMetric());
        try (var statement = connection.prepareStatement("""
                INSERT INTO opspilot.knowledge_embedding
                    (chunk_id, model_revision_id, embedding_dimension, embedding, content_sha256)
                SELECT chunk.chunk_id, revision.model_revision_id, ?, ?::vector, ?
                FROM opspilot.knowledge_chunk chunk
                JOIN opspilot.model_revision revision ON revision.model_revision_id = ?
                WHERE chunk.chunk_id = ?
                  AND chunk.content_sha256 = ?
                  AND revision.embedding_dimension = ?
                  AND revision.distance_metric = ?
                """)) {
            statement.setInt(1, command.dimension());
            statement.setString(2, vectorLiteral(vector.vector()));
            statement.setString(3, vector.contentSha256());
            statement.setObject(4, command.modelRevisionId());
            statement.setObject(5, vector.chunkId());
            statement.setString(6, vector.contentSha256());
            statement.setInt(7, command.dimension());
            statement.setString(8, command.distanceMetric());
            if (statement.executeUpdate() != 1) {
                throw new EmbeddingBatchPersistenceException("EMBEDDING_VECTOR_IDENTITY_MISMATCH");
            }
        }
    }

    private static void updateJob(
            Connection connection, EmbeddingBatchCommit command, String status) throws SQLException {
        try (var statement = connection.prepareStatement("""
                UPDATE opspilot.knowledge_ingestion_job
                SET checkpoint_ordinal = ?, status = ?, attempt_count = attempt_count + 1,
                    last_error = NULL, updated_at = now()
                WHERE job_id = ? AND checkpoint_ordinal < ?
                """)) {
            statement.setInt(1, command.checkpointOrdinal());
            statement.setString(2, status);
            statement.setObject(3, command.jobId());
            statement.setInt(4, command.checkpointOrdinal());
            if (statement.executeUpdate() != 1) {
                throw new EmbeddingBatchPersistenceException("EMBEDDING_CHECKPOINT_CONFLICT");
            }
        }
    }

    private static void updateCoverage(
            Connection connection, UUID jobId, int completedChunks, String coverage) throws SQLException {
        try (var statement = connection.prepareStatement("""
                UPDATE opspilot.knowledge_document_version version
                SET completed_chunk_count = ?, coverage_status = ?
                FROM opspilot.knowledge_ingestion_job job
                WHERE job.job_id = ? AND job.document_version_id = version.document_version_id
                """)) {
            statement.setInt(1, completedChunks);
            statement.setString(2, coverage);
            statement.setObject(3, jobId);
            if (statement.executeUpdate() != 1) {
                throw new EmbeddingBatchPersistenceException("EMBEDDING_COVERAGE_UPDATE_FAILED");
            }
        }
    }

    private static void validateVector(float[] vector, int dimension, String metric) {
        if (vector.length != dimension) {
            throw new EmbeddingBatchPersistenceException("EMBEDDING_DIMENSION_MISMATCH");
        }
        double norm = 0;
        for (float value : vector) {
            if (!Float.isFinite(value)) {
                throw new EmbeddingBatchPersistenceException("EMBEDDING_NON_FINITE");
            }
            norm += value * value;
        }
        if ("COSINE".equals(metric) && norm == 0) {
            throw new EmbeddingBatchPersistenceException("EMBEDDING_ZERO_NORM");
        }
    }

    private static String vectorLiteral(float[] vector) {
        List<String> values = new ArrayList<>(vector.length);
        for (float value : vector) values.add(Float.toString(value));
        return '[' + String.join(",", values) + ']';
    }

    private static float[] parseVector(String literal, int dimension) {
        String[] values = literal.substring(1, literal.length() - 1).split(",");
        if (values.length != dimension) {
            throw new EmbeddingBatchPersistenceException("EMBEDDING_DIMENSION_MISMATCH");
        }
        float[] vector = new float[dimension];
        for (int index = 0; index < values.length; index++) vector[index] = Float.parseFloat(values[index]);
        return vector;
    }

    private record JobIdentity(UUID modelRevisionId, int expectedChunks, int completedChunks) { }

    public static final class EmbeddingBatchPersistenceException extends RuntimeException {
        public EmbeddingBatchPersistenceException(String code) { super(code); }
        public EmbeddingBatchPersistenceException(String code, Throwable cause) { super(code, cause); }
    }
}
