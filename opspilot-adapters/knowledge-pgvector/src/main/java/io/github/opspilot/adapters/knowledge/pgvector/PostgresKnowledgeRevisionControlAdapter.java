package io.github.opspilot.adapters.knowledge.pgvector;

import io.github.opspilot.core.port.knowledge.KnowledgeRevisionControlPort;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL transaction boundary for the controlled, generic Knowledge revision lifecycle. */
public final class PostgresKnowledgeRevisionControlAdapter implements KnowledgeRevisionControlPort {
    private final DataSource dataSource;

    public PostgresKnowledgeRevisionControlAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public Optional<RevisionStatus> findPrepared(
            UUID collectionId, UUID revisionId, String manifestSha256) {
        String sql = """
                SELECT revision.collection_id, revision.knowledge_revision_id, revision.revision_key,
                       revision.manifest_sha256, revision.status, revision.completed_chunk_count,
                       revision.model_revision_id, model.revision_key, revision.expires_at
                FROM opspilot.knowledge_revision revision
                JOIN opspilot.model_revision model ON model.model_revision_id = revision.model_revision_id
                WHERE revision.collection_id = ? AND revision.knowledge_revision_id = ?
                  AND revision.manifest_sha256 = ?
                """;
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            bind(statement, collectionId, revisionId, manifestSha256);
            try (var result = statement.executeQuery()) {
                return result.next() ? Optional.of(status(result)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new ControlPersistenceException("KNOWLEDGE_CONTROL_STATUS_READ_FAILED", failure);
        }
    }

    @Override
    public Optional<UUID> findActiveRevision(UUID collectionId) {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("""
                SELECT active_knowledge_revision_id
                FROM opspilot.knowledge_collection WHERE collection_id = ?
                """)) {
            statement.setObject(1, collectionId);
            try (var result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                return Optional.ofNullable(result.getObject(1, UUID.class));
            }
        } catch (SQLException failure) {
            throw new ControlPersistenceException("KNOWLEDGE_CONTROL_ACTIVE_READ_FAILED", failure);
        }
    }

    @Override
    public Optional<ActivationReceipt> findReceipt(UUID receiptId) {
        try (var connection = dataSource.getConnection()) {
            return findReceipt(connection, receiptId, false);
        } catch (SQLException failure) {
            throw new ControlPersistenceException("KNOWLEDGE_CONTROL_RECEIPT_READ_FAILED", failure);
        }
    }

    @Override
    public RevisionStatus prepare(PreparedRevision revision) {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<RevisionStatus> existing = findRevision(connection, revision.revisionId());
                if (existing.isPresent()) {
                    RevisionStatus status = existing.get();
                    if (!status.collectionId().equals(revision.collectionId())
                            || !Objects.equals(status.manifestSha256(), revision.manifestSha256())) {
                        throw new ControlPersistenceException("KNOWLEDGE_CONTROL_REVISION_IMMUTABLE");
                    }
                    connection.commit();
                    return status;
                }
                validateModelIdentity(connection, revision);
                insertRevision(connection, revision);
                UUID ownerRunId = ensureControlOwner(connection, revision);
                insertRevisionContent(connection, revision, ownerRunId);
                insertAudit(connection, revision.operationId(), revision.principalId(), "PREPARE",
                        revision.collectionId(), revision.revisionId(), null, Instant.now());
                RevisionStatus status = findRevision(connection, revision.revisionId()).orElseThrow();
                connection.commit();
                return status;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (ControlPersistenceException failure) {
            throw failure;
        } catch (SQLException failure) {
            throw new ControlPersistenceException("KNOWLEDGE_CONTROL_PREPARE_FAILED", failure);
        }
    }

    @Override
    public ActivationReceipt activate(
            String operationId, String principalId, UUID collectionId,
            UUID targetRevisionId, UUID expectedActiveRevisionId,
            Instant receiptExpiresAt, Instant activatedAt) {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<ActivationReceipt> existing = findReceiptByActivateOperation(connection, operationId);
                if (existing.isPresent()) {
                    ActivationReceipt receipt = existing.get();
                    if (!receipt.collectionId().equals(collectionId)
                            || !receipt.activatedRevisionId().equals(targetRevisionId)
                            || !Objects.equals(receipt.previousRevisionId(), expectedActiveRevisionId)
                            || !receipt.expiresAt().equals(receiptExpiresAt)) {
                        throw new ControlPersistenceException("KNOWLEDGE_CONTROL_IDEMPOTENCY_CONFLICT");
                    }
                    connection.commit();
                    return receipt;
                }
                UUID active = lockActiveRevision(connection, collectionId);
                if (!Objects.equals(active, expectedActiveRevisionId)) {
                    throw new ControlPersistenceException("KNOWLEDGE_CONTROL_ACTIVE_REVISION_CONFLICT");
                }
                ActivationTarget target = lockActivationTarget(connection, collectionId, targetRevisionId);
                if (!("READY".equals(target.status()) || "RETAINED".equals(target.status()))
                        || !"COMPLETE".equals(target.coverageStatus())
                        || target.expectedChunks() != target.completedChunks()
                        || target.expiresAt() == null || !target.expiresAt().isAfter(activatedAt)) {
                    throw new ControlPersistenceException("KNOWLEDGE_CONTROL_REVISION_NOT_READY");
                }
                deactivateRevision(connection, active);
                activateRevision(connection, targetRevisionId, activatedAt);
                updateOne(connection, """
                        UPDATE opspilot.knowledge_collection
                        SET active_knowledge_revision_id = ?, active_model_revision_id = ?
                        WHERE collection_id = ?
                        """, targetRevisionId, target.modelRevisionId(), collectionId);
                ActivationReceipt receipt = new ActivationReceipt(
                        deterministicId("receipt:" + operationId), collectionId, active,
                        targetRevisionId, receiptExpiresAt, activatedAt, null);
                insertReceipt(connection, operationId, principalId, receipt);
                insertAudit(connection, operationId, principalId, "ACTIVATE", collectionId,
                        targetRevisionId, receipt.receiptId(), activatedAt);
                connection.commit();
                return receipt;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (ControlPersistenceException failure) {
            throw failure;
        } catch (SQLException failure) {
            throw new ControlPersistenceException("KNOWLEDGE_CONTROL_ACTIVATE_FAILED", failure);
        }
    }

    @Override
    public ActivationReceipt restore(
            String operationId, String principalId, ActivationReceipt supplied, Instant restoredAt) {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                ActivationReceipt receipt = findReceipt(connection, supplied.receiptId(), true)
                        .orElseThrow(() -> new ControlPersistenceException("KNOWLEDGE_CONTROL_RECEIPT_NOT_FOUND"));
                if (receipt.restoredAt() != null) {
                    connection.commit();
                    return receipt;
                }
                UUID active = lockActiveRevision(connection, receipt.collectionId());
                if (!Objects.equals(active, receipt.activatedRevisionId())) {
                    throw new ControlPersistenceException("KNOWLEDGE_CONTROL_RESTORE_CONFLICT");
                }
                deactivateRevision(connection, receipt.activatedRevisionId());
                UUID previous = receipt.previousRevisionId();
                if (previous == null) {
                    updateOne(connection, """
                            UPDATE opspilot.knowledge_collection
                            SET active_knowledge_revision_id = NULL
                            WHERE collection_id = ?
                            """, receipt.collectionId());
                } else {
                    ActivationTarget target = lockActivationTarget(
                            connection, receipt.collectionId(), previous);
                    activateRevision(connection, previous, restoredAt);
                    updateOne(connection, """
                            UPDATE opspilot.knowledge_collection
                            SET active_knowledge_revision_id = ?, active_model_revision_id = ?
                            WHERE collection_id = ?
                            """, previous, target.modelRevisionId(), receipt.collectionId());
                }
                updateOne(connection, """
                        UPDATE opspilot.knowledge_activation_receipt
                        SET restore_operation_id = ?, restored_by = ?, restored_at = ?
                        WHERE receipt_id = ? AND restored_at IS NULL
                        """, operationId, principalId, restoredAt, receipt.receiptId());
                insertAudit(connection, operationId, principalId, "RESTORE", receipt.collectionId(),
                        receipt.activatedRevisionId(), receipt.receiptId(), restoredAt);
                connection.commit();
                return new ActivationReceipt(
                        receipt.receiptId(), receipt.collectionId(), receipt.previousRevisionId(),
                        receipt.activatedRevisionId(), receipt.expiresAt(), receipt.activatedAt(), restoredAt);
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (ControlPersistenceException failure) {
            throw failure;
        } catch (SQLException failure) {
            throw new ControlPersistenceException("KNOWLEDGE_CONTROL_RESTORE_FAILED", failure);
        }
    }

    private static void validateModelIdentity(Connection connection, PreparedRevision revision)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT revision_key, embedding_dimension, distance_metric
                FROM opspilot.model_revision WHERE model_revision_id = ?
                """)) {
            statement.setObject(1, revision.modelRevisionId());
            try (var result = statement.executeQuery()) {
                if (!result.next()
                        || !revision.modelRevisionKey().equals(result.getString(1))
                        || revision.embeddingDimension() != result.getInt(2)
                        || !revision.distanceMetric().equals(result.getString(3))) {
                    throw new ControlPersistenceException("KNOWLEDGE_CONTROL_MODEL_IDENTITY_MISMATCH");
                }
            }
        }
    }

    private static void insertRevision(Connection connection, PreparedRevision revision) throws SQLException {
        updateOne(connection, """
                INSERT INTO opspilot.knowledge_revision
                    (knowledge_revision_id, collection_id, status, normalization_version,
                     chunk_strategy_version, model_revision_id, embedding_dimension,
                     coverage_status, expected_chunk_count, completed_chunk_count,
                     searchable, retain_until, revision_key, manifest_sha256, expires_at)
                VALUES (?, ?, 'READY', 'knowledge-control-v1', 'knowledge-control-v1', ?, ?,
                        'COMPLETE', ?, ?, false, ?, ?, ?, ?)
                """, revision.revisionId(), revision.collectionId(), revision.modelRevisionId(),
                revision.embeddingDimension(), revision.chunks().size(), revision.chunks().size(),
                revision.expiresAt(), revision.revisionKey(), revision.manifestSha256(), revision.expiresAt());
    }

    private static UUID ensureControlOwner(Connection connection, PreparedRevision revision) throws SQLException {
        UUID incidentId = deterministicId("control-incident:" + revision.collectionId());
        UUID runId = deterministicId("control-run:" + revision.revisionId());
        String targetSystemId = "knowledge-control:" + revision.collectionId();
        updateAny(connection, """
                INSERT INTO opspilot.target_system (target_system_id, display_name)
                VALUES (?, 'Knowledge revision control') ON CONFLICT (target_system_id) DO NOTHING
                """, targetSystemId);
        updateAny(connection, """
                INSERT INTO opspilot.incident (incident_id, target_system_id, status)
                VALUES (?, ?, 'RESOLVED') ON CONFLICT (incident_id) DO NOTHING
                """, incidentId, targetSystemId);
        updateAny(connection, """
                INSERT INTO opspilot.incident_run (run_id, incident_id, status, ended_at)
                VALUES (?, ?, 'COMPLETED', now()) ON CONFLICT (run_id) DO NOTHING
                """, runId, incidentId);
        return runId;
    }

    private static void insertRevisionContent(
            Connection connection, PreparedRevision revision, UUID runId) throws SQLException {
        Map<String, List<PreparedChunk>> documents = new LinkedHashMap<>();
        for (PreparedChunk chunk : revision.chunks()) {
            documents.computeIfAbsent(chunk.externalKey(), ignored -> new ArrayList<>()).add(chunk);
        }
        for (var entry : documents.entrySet()) {
            UUID documentId = deterministicId("document:" + revision.collectionId() + ":" + entry.getKey());
            updateAny(connection, """
                    INSERT INTO opspilot.knowledge_document
                        (document_id, collection_id, external_key, status, metadata_json)
                    VALUES (?, ?, ?, 'INACTIVE', '{"schemaVersion":"1.0.0"}'::jsonb)
                    ON CONFLICT (collection_id, external_key) DO NOTHING
                    """, documentId, revision.collectionId(), entry.getKey());
            int versionNumber = nextVersionNumber(connection, documentId);
            UUID versionId = deterministicId("version:" + revision.revisionId() + ":" + entry.getKey());
            PreparedChunk first = entry.getValue().getFirst();
            UUID sourceArtifactId = insertArtifact(connection, runId, revision, first);
            updateOne(connection, """
                    INSERT INTO opspilot.knowledge_document_version
                        (document_version_id, document_id, version_number, status, content_sha256,
                         source_artifact_id, coverage_status, expected_chunk_count,
                         completed_chunk_count, retain_until, knowledge_revision_id,
                         normalization_version, chunk_strategy_version, acl_json)
                    VALUES (?, ?, ?, 'RETAINED', ?, ?, 'COMPLETE', ?, ?, ?, ?,
                            'knowledge-control-v1', 'knowledge-control-v1', ?::jsonb)
                    """, versionId, documentId, versionNumber, revision.manifestSha256(), sourceArtifactId,
                    entry.getValue().size(), entry.getValue().size(), revision.expiresAt(),
                    revision.revisionId(), aclJson(first.aclPrincipals()));
            int ordinal = 0;
            for (PreparedChunk chunk : entry.getValue()) {
                UUID artifactId = chunk == first ? sourceArtifactId : insertArtifact(connection, runId, revision, chunk);
                updateOne(connection, """
                        INSERT INTO opspilot.knowledge_chunk
                            (chunk_id, document_version_id, ordinal, content_sha256,
                             content_artifact_id, searchable, metadata_json, source_location, acl_json)
                        VALUES (?, ?, ?, ?, ?, false, ?::jsonb, ?, ?::jsonb)
                        """, chunk.chunkId(), versionId, ordinal++, chunk.contentSha256(), artifactId,
                        metadataJson(chunk.metadata()), "knowledge-control://" + revision.revisionId()
                                + "/" + chunk.chunkId(), aclJson(chunk.aclPrincipals()));
                updateOne(connection, """
                        INSERT INTO opspilot.knowledge_embedding
                            (chunk_id, model_revision_id, embedding_dimension, embedding, content_sha256)
                        VALUES (?, ?, ?, ?::vector, ?)
                        """, chunk.chunkId(), revision.modelRevisionId(), revision.embeddingDimension(),
                        vectorLiteral(chunk.embedding()), chunk.contentSha256());
            }
        }
    }

    private static UUID insertArtifact(
            Connection connection, UUID runId, PreparedRevision revision, PreparedChunk chunk) throws SQLException {
        UUID artifactId = deterministicId("artifact:" + revision.revisionId() + ":" + chunk.chunkId());
        updateAny(connection, """
                INSERT INTO opspilot.artifact
                    (artifact_id, run_id, uri, sha256, media_type, access_level,
                     object_key, size_bytes)
                VALUES (?, ?, ?, ?, 'text/plain; charset=utf-8', 'INTERNAL', ?, ?)
                ON CONFLICT (artifact_id) DO NOTHING
                """, artifactId, runId,
                "knowledge-control://" + revision.revisionId() + "/" + chunk.chunkId(),
                chunk.contentSha256(), "knowledge-control/" + revision.revisionId()
                        + "/" + chunk.chunkId(),
                chunk.text().getBytes(StandardCharsets.UTF_8).length);
        return artifactId;
    }

    private static int nextVersionNumber(Connection connection, UUID documentId) throws SQLException {
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

    private static void deactivateRevision(Connection connection, UUID revisionId) throws SQLException {
        if (revisionId == null) return;
        updateAny(connection, """
                UPDATE opspilot.knowledge_chunk SET searchable = false
                WHERE document_version_id IN (
                    SELECT document_version_id FROM opspilot.knowledge_document_version
                    WHERE knowledge_revision_id = ?)
                """, revisionId);
        updateAny(connection, """
                UPDATE opspilot.knowledge_document SET active_version_id = NULL, status = 'INACTIVE'
                WHERE active_version_id IN (
                    SELECT document_version_id FROM opspilot.knowledge_document_version
                    WHERE knowledge_revision_id = ?)
                """, revisionId);
        updateAny(connection, """
                UPDATE opspilot.knowledge_document_version SET status = 'RETAINED'
                WHERE knowledge_revision_id = ? AND status = 'ACTIVE'
                """, revisionId);
        updateAny(connection, """
                UPDATE opspilot.knowledge_revision
                SET status = 'RETAINED', searchable = false
                WHERE knowledge_revision_id = ? AND status = 'ACTIVE'
                """, revisionId);
    }

    private static void activateRevision(Connection connection, UUID revisionId, Instant activatedAt)
            throws SQLException {
        updateAny(connection, """
                UPDATE opspilot.knowledge_document_version SET status = 'ACTIVE'
                WHERE knowledge_revision_id = ?
                """, revisionId);
        updateAny(connection, """
                UPDATE opspilot.knowledge_chunk SET searchable = true
                WHERE document_version_id IN (
                    SELECT document_version_id FROM opspilot.knowledge_document_version
                    WHERE knowledge_revision_id = ?)
                  AND deleted_at IS NULL
                """, revisionId);
        updateAny(connection, """
                UPDATE opspilot.knowledge_document document
                SET active_version_id = version.document_version_id, status = 'ACTIVE'
                FROM opspilot.knowledge_document_version version
                WHERE version.document_id = document.document_id
                  AND version.knowledge_revision_id = ?
                """, revisionId);
        updateOne(connection, """
                UPDATE opspilot.knowledge_revision
                SET status = 'ACTIVE', searchable = true, activated_at = ?
                WHERE knowledge_revision_id = ?
                """, activatedAt, revisionId);
    }

    private static UUID lockActiveRevision(Connection connection, UUID collectionId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT active_knowledge_revision_id
                FROM opspilot.knowledge_collection WHERE collection_id = ? FOR UPDATE
                """)) {
            statement.setObject(1, collectionId);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new ControlPersistenceException("KNOWLEDGE_CONTROL_COLLECTION_NOT_FOUND");
                }
                return result.getObject(1, UUID.class);
            }
        }
    }

    private static ActivationTarget lockActivationTarget(
            Connection connection, UUID collectionId, UUID revisionId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT status, coverage_status, expected_chunk_count, completed_chunk_count,
                       model_revision_id, expires_at
                FROM opspilot.knowledge_revision
                WHERE collection_id = ? AND knowledge_revision_id = ? FOR UPDATE
                """)) {
            bind(statement, collectionId, revisionId);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new ControlPersistenceException("KNOWLEDGE_CONTROL_REVISION_NOT_FOUND");
                }
                return new ActivationTarget(
                        result.getString(1), result.getString(2), result.getInt(3), result.getInt(4),
                        result.getObject(5, UUID.class), instant(result, 6));
            }
        }
    }

    private static void insertReceipt(
            Connection connection, String operationId, String principalId, ActivationReceipt receipt)
            throws SQLException {
        updateOne(connection, """
                INSERT INTO opspilot.knowledge_activation_receipt
                    (receipt_id, collection_id, previous_revision_id, activated_revision_id,
                     activate_operation_id, activated_by, expires_at, activated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, receipt.receiptId(), receipt.collectionId(), receipt.previousRevisionId(),
                receipt.activatedRevisionId(), operationId, principalId,
                receipt.expiresAt(), receipt.activatedAt());
    }

    private static Optional<ActivationReceipt> findReceiptByActivateOperation(
            Connection connection, String operationId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT receipt_id, collection_id, previous_revision_id, activated_revision_id,
                       expires_at, activated_at, restored_at
                FROM opspilot.knowledge_activation_receipt WHERE activate_operation_id = ?
                """)) {
            statement.setString(1, operationId);
            try (var result = statement.executeQuery()) {
                return result.next() ? Optional.of(receipt(result)) : Optional.empty();
            }
        }
    }

    private static Optional<ActivationReceipt> findReceipt(
            Connection connection, UUID receiptId, boolean lock) throws SQLException {
        String sql = """
                SELECT receipt_id, collection_id, previous_revision_id, activated_revision_id,
                       expires_at, activated_at, restored_at
                FROM opspilot.knowledge_activation_receipt WHERE receipt_id = ?
                """ + (lock ? " FOR UPDATE" : "");
        try (var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, receiptId);
            try (var result = statement.executeQuery()) {
                return result.next() ? Optional.of(receipt(result)) : Optional.empty();
            }
        }
    }

    private static Optional<RevisionStatus> findRevision(Connection connection, UUID revisionId)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT revision.collection_id, revision.knowledge_revision_id, revision.revision_key,
                       revision.manifest_sha256, revision.status, revision.completed_chunk_count,
                       revision.model_revision_id, model.revision_key, revision.expires_at
                FROM opspilot.knowledge_revision revision
                JOIN opspilot.model_revision model ON model.model_revision_id = revision.model_revision_id
                WHERE revision.knowledge_revision_id = ?
                """)) {
            statement.setObject(1, revisionId);
            try (var result = statement.executeQuery()) {
                return result.next() ? Optional.of(status(result)) : Optional.empty();
            }
        }
    }

    private static RevisionStatus status(ResultSet result) throws SQLException {
        return new RevisionStatus(
                result.getObject(1, UUID.class), result.getObject(2, UUID.class),
                result.getString(3), result.getString(4), result.getString(5), result.getInt(6),
                result.getObject(7, UUID.class), result.getString(8), instant(result, 9));
    }

    private static ActivationReceipt receipt(ResultSet result) throws SQLException {
        return new ActivationReceipt(
                result.getObject(1, UUID.class), result.getObject(2, UUID.class),
                result.getObject(3, UUID.class), result.getObject(4, UUID.class),
                instant(result, 5), instant(result, 6), instant(result, 7));
    }

    private static Instant instant(ResultSet result, int index) throws SQLException {
        Timestamp timestamp = result.getTimestamp(index);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static void insertAudit(
            Connection connection, String operationId, String principalId, String action,
            UUID collectionId, UUID revisionId, UUID receiptId, Instant occurredAt) throws SQLException {
        updateAny(connection, """
                INSERT INTO opspilot.knowledge_revision_control_audit
                    (audit_id, operation_id, principal_id, action, collection_id,
                     revision_id, receipt_id, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (operation_id, action) DO NOTHING
                """, deterministicId("audit:" + action + ":" + operationId), operationId,
                principalId, action, collectionId, revisionId, receiptId, occurredAt);
    }

    private static String metadataJson(Map<String, String> metadata) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("schemaVersion", "1.0.0");
        values.putAll(metadata);
        return jsonObject(values);
    }

    private static String aclJson(List<String> principals) {
        StringBuilder json = new StringBuilder("{\"principals\":[");
        for (int index = 0; index < principals.size(); index++) {
            if (index > 0) json.append(',');
            json.append('"').append(escapeJson(principals.get(index))).append('"');
        }
        return json.append("]}").toString();
    }

    private static String jsonObject(Map<String, String> values) {
        StringBuilder json = new StringBuilder("{");
        int index = 0;
        for (var entry : values.entrySet()) {
            if (index++ > 0) json.append(',');
            json.append('"').append(escapeJson(entry.getKey())).append("\":\"")
                    .append(escapeJson(entry.getValue())).append('"');
        }
        return json.append('}').toString();
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private static String vectorLiteral(float[] vector) {
        StringBuilder result = new StringBuilder("[");
        for (int index = 0; index < vector.length; index++) {
            if (index > 0) result.append(',');
            result.append(Float.toString(vector[index]));
        }
        return result.append(']').toString();
    }

    private static UUID deterministicId(String value) {
        return UUID.nameUUIDFromBytes(("opspilot:" + value).getBytes(StandardCharsets.UTF_8));
    }

    private static void updateOne(Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            if (statement.executeUpdate() != 1) {
                throw new ControlPersistenceException("KNOWLEDGE_CONTROL_WRITE_CONFLICT");
            }
        }
    }

    private static void updateAny(Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            statement.executeUpdate();
        }
    }

    private static void bind(PreparedStatement statement, Object... values) throws SQLException {
        for (int index = 0; index < values.length; index++) {
            Object value = values[index];
            if (value instanceof Instant instant) statement.setTimestamp(index + 1, Timestamp.from(instant));
            else statement.setObject(index + 1, value);
        }
    }

    private record ActivationTarget(
            String status, String coverageStatus, int expectedChunks, int completedChunks,
            UUID modelRevisionId, Instant expiresAt) {
    }

    public static final class ControlPersistenceException extends RuntimeException {
        public ControlPersistenceException(String code) {
            super(code);
        }

        public ControlPersistenceException(String code, Throwable cause) {
            super(code, cause);
        }
    }
}
