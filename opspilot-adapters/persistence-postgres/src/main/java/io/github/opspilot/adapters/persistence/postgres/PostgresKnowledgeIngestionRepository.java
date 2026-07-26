package io.github.opspilot.adapters.persistence.postgres;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opspilot.core.port.knowledge.KnowledgeIngestionPort;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Transactional persistence for an accepted immutable knowledge ingestion request. */
public final class PostgresKnowledgeIngestionRepository implements KnowledgeIngestionPort {
    private final DataSource dataSource;
    private final ObjectMapper json;

    public PostgresKnowledgeIngestionRepository(DataSource dataSource, ObjectMapper json) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    public Optional<AcceptedIngestion> findByIdempotencyKey(String idempotencyKey) {
        String sql = """
                SELECT idempotency_key, request_sha256, document_id, document_version_id, job_id
                FROM opspilot.knowledge_ingestion_request WHERE idempotency_key = ?
                """;
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setString(1, idempotencyKey);
            try (var result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                return Optional.of(new AcceptedIngestion(result.getString(1), result.getString(2),
                        result.getObject(3, UUID.class), result.getObject(4, UUID.class),
                        result.getObject(5, UUID.class)));
            }
        } catch (SQLException failure) {
            throw new KnowledgeIngestionPersistenceException("KNOWLEDGE_IDEMPOTENCY_READ_FAILED", failure);
        }
    }

    @Override
    public AcceptedIngestion accept(IngestionRevision revision) {
        Objects.requireNonNull(revision, "revision");
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<AcceptedIngestion> existing = findLocked(connection,
                        revision.identity().idempotencyKey());
                if (existing.isPresent()) {
                    if (!existing.get().requestSha256().equals(revision.identity().requestSha256())) {
                        throw new KnowledgeIngestionPersistenceException("KNOWLEDGE_IDEMPOTENCY_CONFLICT");
                    }
                    connection.rollback();
                    return existing.get();
                }
                UUID documentId = findDocument(connection, revision.collectionId(), revision.externalKey())
                        .orElse(revision.identity().documentId());
                if (documentId.equals(revision.identity().documentId())) {
                    update(connection, """
                            INSERT INTO opspilot.knowledge_document
                                (document_id, collection_id, external_key, status)
                            VALUES (?, ?, ?, 'INACTIVE')
                            """, documentId, revision.collectionId(), revision.externalKey());
                }
                int versionNumber = nextVersion(connection, documentId);
                UUID knowledgeRevisionId = UUID.randomUUID();
                update(connection, """
                        INSERT INTO opspilot.knowledge_revision
                            (knowledge_revision_id, collection_id, status, normalization_version,
                             chunk_strategy_version, model_revision_id, embedding_dimension,
                             coverage_status, expected_chunk_count)
                        VALUES (?, ?, 'BUILDING', ?, ?, ?, ?, 'PENDING', ?)
                        """, knowledgeRevisionId, revision.collectionId(), revision.normalizationVersion(),
                        revision.chunkStrategyVersion(), revision.modelRevisionId(),
                        revision.embeddingDimension(), revision.chunks().size());
                String aclJson = aclJson(revision);
                update(connection, """
                        INSERT INTO opspilot.knowledge_document_version
                            (document_version_id, document_id, version_number, status, content_sha256,
                             source_artifact_id, coverage_status, expected_chunk_count,
                             knowledge_revision_id, normalization_version, chunk_strategy_version, acl_json)
                        VALUES (?, ?, ?, 'BUILDING', ?, ?, 'PENDING', ?, ?, ?, ?, ?::jsonb)
                        """, revision.identity().documentVersionId(), documentId, versionNumber,
                        revision.contentSha256(), revision.sourceArtifactId().value(), revision.chunks().size(),
                        knowledgeRevisionId, revision.normalizationVersion(),
                        revision.chunkStrategyVersion(), aclJson);
                for (ChunkWrite chunk : revision.chunks()) {
                    update(connection, """
                            INSERT INTO opspilot.knowledge_chunk
                                (chunk_id, document_version_id, ordinal, content_sha256,
                                 content_artifact_id, source_location, acl_json)
                            VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
                            """, chunk.chunkId(), revision.identity().documentVersionId(), chunk.ordinal(),
                            chunk.contentSha256(), chunk.artifactId().value(), chunk.location(), aclJson);
                }
                update(connection, """
                        INSERT INTO opspilot.knowledge_ingestion_job
                            (job_id, document_version_id, model_revision_id, status)
                        VALUES (?, ?, ?, 'PENDING')
                        """, revision.identity().jobId(), revision.identity().documentVersionId(),
                        revision.modelRevisionId());
                update(connection, """
                        INSERT INTO opspilot.knowledge_ingestion_request
                            (idempotency_key, request_sha256, document_id, document_version_id, job_id)
                        VALUES (?, ?, ?, ?, ?)
                        """, revision.identity().idempotencyKey(), revision.identity().requestSha256(),
                        documentId, revision.identity().documentVersionId(), revision.identity().jobId());
                connection.commit();
                return new AcceptedIngestion(revision.identity().idempotencyKey(),
                        revision.identity().requestSha256(), documentId,
                        revision.identity().documentVersionId(), revision.identity().jobId());
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException failure) {
            throw new KnowledgeIngestionPersistenceException("KNOWLEDGE_INGESTION_WRITE_FAILED", failure);
        }
    }

    private Optional<AcceptedIngestion> findLocked(Connection connection, String key) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT idempotency_key, request_sha256, document_id, document_version_id, job_id
                FROM opspilot.knowledge_ingestion_request WHERE idempotency_key = ? FOR UPDATE
                """)) {
            statement.setString(1, key);
            try (var result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                return Optional.of(new AcceptedIngestion(result.getString(1), result.getString(2),
                        result.getObject(3, UUID.class), result.getObject(4, UUID.class),
                        result.getObject(5, UUID.class)));
            }
        }
    }

    private static Optional<UUID> findDocument(
            Connection connection, UUID collectionId, String externalKey) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT document_id FROM opspilot.knowledge_document
                WHERE collection_id = ? AND external_key = ? FOR UPDATE
                """)) {
            statement.setObject(1, collectionId);
            statement.setString(2, externalKey);
            try (var result = statement.executeQuery()) {
                return result.next() ? Optional.of(result.getObject(1, UUID.class)) : Optional.empty();
            }
        }
    }

    private static int nextVersion(Connection connection, UUID documentId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT coalesce(max(version_number), 0) + 1
                FROM opspilot.knowledge_document_version WHERE document_id = ?
                """)) {
            statement.setObject(1, documentId);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private String aclJson(IngestionRevision revision) {
        try {
            return json.writeValueAsString(Map.of("principals", revision.aclPrincipals()));
        } catch (JsonProcessingException impossible) {
            throw new KnowledgeIngestionPersistenceException("KNOWLEDGE_ACL_SERIALIZATION_FAILED", impossible);
        }
    }

    private static void update(Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            if (statement.executeUpdate() != 1) {
                throw new KnowledgeIngestionPersistenceException("KNOWLEDGE_INGESTION_WRITE_CONFLICT");
            }
        }
    }

    public static final class KnowledgeIngestionPersistenceException extends RuntimeException {
        public KnowledgeIngestionPersistenceException(String code) { super(code); }
        public KnowledgeIngestionPersistenceException(String code, Throwable cause) { super(code, cause); }
    }
}
