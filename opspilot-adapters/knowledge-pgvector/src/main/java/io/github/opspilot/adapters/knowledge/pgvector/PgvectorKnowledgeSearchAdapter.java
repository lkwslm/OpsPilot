package io.github.opspilot.adapters.knowledge.pgvector;

import io.github.opspilot.adapters.knowledge.pgvector.PgvectorKnowledgeRepository.DistanceMetric;
import io.github.opspilot.core.application.knowledge.KnowledgeSearchService.CandidateQuery;
import io.github.opspilot.core.application.knowledge.KnowledgeSearchService.CandidateQueryPort;
import io.github.opspilot.core.application.knowledge.KnowledgeSearchService.KnowledgeCandidate;
import io.github.opspilot.core.application.knowledge.KnowledgeSearchService.KnowledgeCatalogPort;
import io.github.opspilot.core.application.knowledge.KnowledgeSearchService.KnowledgeSnapshot;
import io.github.opspilot.core.domain.identity.DomainIds.ArtifactId;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Bridges the core search use case to exact pgvector recall and controlled Artifact text access. */
public final class PgvectorKnowledgeSearchAdapter implements KnowledgeCatalogPort, CandidateQueryPort {
    private final DataSource dataSource;
    private final PgvectorKnowledgeRepository vectors;
    private final ChunkTextPort text;

    public PgvectorKnowledgeSearchAdapter(DataSource dataSource, ChunkTextPort text) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.vectors = new PgvectorKnowledgeRepository(dataSource);
        this.text = Objects.requireNonNull(text, "text");
    }

    @Override
    public boolean hasSearchableDocuments(KnowledgeSnapshot snapshot, Set<String> aclPrincipals) {
        StringBuilder sql = new StringBuilder("""
                SELECT 1
                FROM opspilot.knowledge_document_version version
                JOIN opspilot.knowledge_document document ON document.document_id = version.document_id
                JOIN opspilot.knowledge_chunk chunk ON chunk.document_version_id = version.document_version_id
                JOIN opspilot.knowledge_revision revision
                  ON revision.knowledge_revision_id = version.knowledge_revision_id
                WHERE document.collection_id = ? AND revision.knowledge_revision_id = ?
                  AND revision.status IN ('ACTIVE','RETAINED') AND version.status = 'ACTIVE'
                  AND document.deleted_at IS NULL AND chunk.deleted_at IS NULL AND chunk.searchable
                """);
        if (!aclPrincipals.isEmpty()) {
            sql.append(" AND EXISTS (SELECT 1 FROM jsonb_array_elements_text(version.acl_json -> 'principals') principal")
                    .append(" WHERE principal.value = ANY (?::text[]))");
        }
        sql.append(" LIMIT 1");
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql.toString())) {
            statement.setObject(1, snapshot.collectionId());
            statement.setObject(2, snapshot.knowledgeRevisionId());
            if (!aclPrincipals.isEmpty()) {
                statement.setArray(3, connection.createArrayOf("text", aclPrincipals.toArray(String[]::new)));
            }
            try (var result = statement.executeQuery()) {
                return result.next();
            }
        } catch (SQLException failure) {
            throw new PgvectorKnowledgeRepository.VectorContractException(
                    "KNOWLEDGE_CATALOG_QUERY_FAILED", failure);
        }
    }

    @Override
    public List<KnowledgeCandidate> exactCandidates(CandidateQuery query) {
        var request = new PgvectorKnowledgeRepository.SearchRequest(
                query.snapshot().collectionId(), query.snapshot().modelRevisionId(),
                query.snapshot().dimension(), DistanceMetric.valueOf(query.snapshot().distanceMetric()),
                query.queryVector(), query.candidateK(), query.filters(),
                query.snapshot().knowledgeRevisionId(), query.aclPrincipals());
        return vectors.exactTopK(request).stream().map(hit -> new KnowledgeCandidate(
                hit.documentId(), hit.documentVersionId(), hit.chunkId(),
                new ArtifactId(hit.contentArtifactId()), hit.sourceLocation(), hit.contentSha256(),
                text.load(new ArtifactId(hit.contentArtifactId()), hit.sourceLocation()), hit.distance())).toList();
    }

    @FunctionalInterface
    public interface ChunkTextPort {
        String load(ArtifactId artifactId, String location);
    }
}
