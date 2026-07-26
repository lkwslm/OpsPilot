package io.github.opspilot.adapters.knowledge.pgvector;

import io.github.opspilot.core.application.knowledge.KnowledgeSearchService.KnowledgeReference;
import io.github.opspilot.core.application.knowledge.KnowledgeSearchService.KnowledgeReferencePort;
import io.github.opspilot.core.domain.identity.DomainIds.ArtifactId;
import io.github.opspilot.core.port.provider.ProviderContracts.ProviderIdentity;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Immutable KnowledgeReference persistence and ACL/integrity checked resolution. */
public final class PostgresKnowledgeReferenceRepository implements KnowledgeReferencePort {
    private final DataSource dataSource;

    public PostgresKnowledgeReferenceRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public void persist(UUID runId, KnowledgeReference reference) {
        String sql = """
                INSERT INTO opspilot.knowledge_reference
                    (knowledge_reference_id, run_id, collection_id, document_id, document_version_id,
                     chunk_id, knowledge_revision_id, model_revision_id, artifact_id, source_location,
                     content_sha256, filter_summary_sha256, vector_distance, rerank_score, rerank_rank,
                     rerank_provider_id, rerank_model_id, rerank_revision)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setObject(index++, reference.referenceId());
            statement.setObject(index++, runId);
            statement.setObject(index++, reference.collectionId());
            statement.setObject(index++, reference.documentId());
            statement.setObject(index++, reference.documentVersionId());
            statement.setObject(index++, reference.chunkId());
            statement.setObject(index++, reference.knowledgeRevisionId());
            statement.setObject(index++, reference.modelRevisionId());
            statement.setObject(index++, reference.artifactId().value());
            statement.setString(index++, reference.location());
            statement.setString(index++, reference.contentSha256());
            statement.setString(index++, reference.filterSummarySha256());
            statement.setDouble(index++, reference.vectorDistance());
            statement.setDouble(index++, reference.rerankScore());
            statement.setInt(index++, reference.rerankRank());
            statement.setString(index++, reference.rerankIdentity().providerId());
            statement.setString(index++, reference.rerankIdentity().modelId());
            statement.setString(index, reference.rerankIdentity().revision());
            if (statement.executeUpdate() != 1) throw new ReferenceException("REFERENCE_WRITE_CONFLICT");
        } catch (SQLException failure) {
            throw new ReferenceException("REFERENCE_WRITE_FAILED", failure);
        }
    }

    public Optional<KnowledgeReference> resolve(UUID referenceId, UUID runId, Set<String> aclPrincipals) {
        if (aclPrincipals.isEmpty()) return Optional.empty();
        String sql = """
                SELECT reference.collection_id, reference.document_id, reference.document_version_id,
                       reference.chunk_id, reference.knowledge_revision_id, reference.model_revision_id,
                       reference.artifact_id, reference.source_location, reference.content_sha256,
                       reference.filter_summary_sha256, reference.vector_distance, reference.rerank_score,
                       reference.rerank_rank, reference.rerank_provider_id, reference.rerank_model_id,
                       reference.rerank_revision
                FROM opspilot.knowledge_reference reference
                JOIN opspilot.knowledge_chunk chunk ON chunk.chunk_id = reference.chunk_id
                JOIN opspilot.knowledge_document_version version
                  ON version.document_version_id = reference.document_version_id
                WHERE reference.knowledge_reference_id = ? AND reference.run_id = ?
                  AND chunk.document_version_id = reference.document_version_id
                  AND chunk.content_artifact_id = reference.artifact_id
                  AND chunk.content_sha256 = reference.content_sha256
                  AND chunk.source_location = reference.source_location
                  AND version.knowledge_revision_id = reference.knowledge_revision_id
                  AND EXISTS (SELECT 1 FROM jsonb_array_elements_text(version.acl_json -> 'principals') principal
                              WHERE principal.value = ANY (?::text[]))
                """;
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, referenceId);
            statement.setObject(2, runId);
            statement.setArray(3, connection.createArrayOf("text", aclPrincipals.toArray(String[]::new)));
            try (var result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                return Optional.of(new KnowledgeReference(referenceId,
                        result.getObject(1, UUID.class), result.getObject(2, UUID.class),
                        result.getObject(3, UUID.class), result.getObject(4, UUID.class),
                        result.getObject(5, UUID.class), result.getObject(6, UUID.class),
                        new ArtifactId(result.getObject(7, UUID.class)), result.getString(8), result.getString(9),
                        result.getString(10), result.getDouble(11), result.getDouble(12), result.getInt(13),
                        new ProviderIdentity(result.getString(14), result.getString(15), result.getString(16))));
            }
        } catch (SQLException failure) {
            throw new ReferenceException("REFERENCE_RESOLUTION_FAILED", failure);
        }
    }

    public static final class ReferenceException extends RuntimeException {
        public ReferenceException(String code) { super(code); }
        public ReferenceException(String code, Throwable cause) { super(code, cause); }
    }
}
