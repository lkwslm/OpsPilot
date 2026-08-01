package io.github.opspilot.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opspilot.adapters.knowledge.pgvector.KnowledgeRevisionRepository;
import io.github.opspilot.adapters.knowledge.pgvector.PgvectorKnowledgeSearchAdapter;
import io.github.opspilot.adapters.knowledge.pgvector.PostgresKnowledgeReferenceRepository;
import io.github.opspilot.adapters.retrieval.infinity.InfinityEmbeddingAdapter;
import io.github.opspilot.adapters.retrieval.infinity.InfinityEmbeddingConfiguration;
import io.github.opspilot.adapters.retrieval.infinity.InfinityRerankAdapter;
import io.github.opspilot.adapters.retrieval.infinity.InfinityRerankConfiguration;
import io.github.opspilot.core.application.knowledge.KnowledgeSearchService;
import io.github.opspilot.core.application.knowledge.KnowledgeSearchService.KnowledgeSnapshot;
import io.github.opspilot.core.application.knowledge.KnowledgeSearchService.SearchRequest;
import io.github.opspilot.core.domain.identity.DomainIds.ArtifactId;
import io.github.opspilot.core.port.provider.ProviderContracts.ProviderIdentity;

import javax.sql.DataSource;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Runs the mandatory Infinity embedding -> exact pgvector -> Infinity rerank chain. */
final class Phase7KnowledgeRetriever {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private final DataSource dataSource;
    private final Map<String, String> environment;

    Phase7KnowledgeRetriever(DataSource dataSource, Map<String, String> environment) {
        this.dataSource = dataSource;
        this.environment = Map.copyOf(environment);
    }

    String search(String requestJson, UUID stepId) throws Exception {
        JsonNode request = JSON.readTree(requestJson);
        UUID runId = UUID.fromString(request.path("runId").asText());
        UUID collectionId = activeCollection();
        var effective = new KnowledgeRevisionRepository(dataSource)
                .requireRunSnapshot(runId);
        String embeddingModel = required("EMBEDDING_MODEL_ID");
        String embeddingRevision = required("EMBEDDING_MODEL_REVISION");
        String rerankModel = required("RERANK_MODEL_ID");
        String rerankRevision = required("RERANK_MODEL_REVISION");
        ProviderIdentity embeddingIdentity = new ProviderIdentity(
                "infinity", embeddingModel, embeddingRevision);
        ProviderIdentity rerankIdentity = new ProviderIdentity(
                "infinity", rerankModel, rerankRevision);
        var embedding = new InfinityEmbeddingAdapter(new InfinityEmbeddingConfiguration(
                "infinity", URI.create(required("EMBEDDING_BASE_URL")), embeddingModel,
                embeddingRevision, effective.dimension(),
                InfinityEmbeddingConfiguration.Normalization.L2_UNIT,
                InfinityEmbeddingConfiguration.DistanceMetric.COSINE,
                16, 16, 8192), text -> Math.max(1, (text.length() + 1) / 2));
        var rerank = new InfinityRerankAdapter(new InfinityRerankConfiguration(
                URI.create(required("RERANK_BASE_URL").replaceAll("/+$", "") + "/rerank"),
                "infinity", rerankModel, rerankRevision));
        var pgvector = new PgvectorKnowledgeSearchAdapter(dataSource, this::chunkText);
        var service = new KnowledgeSearchService(
                pgvector, embedding, pgvector, rerank,
                new PostgresKnowledgeReferenceRepository(dataSource),
                audit -> System.out.printf(
                        "KNOWLEDGE_AUDIT runId=%s outcome=%s candidates=%d reranked=%d%n",
                        audit.runId(), audit.outcome(), audit.candidateCount(), audit.rerankCount()));
        KnowledgeSnapshot snapshot = new KnowledgeSnapshot(
                collectionId, effective.knowledgeRevisionId(), effective.modelRevisionId(),
                effective.dimension(), "COSINE", embeddingIdentity, rerankIdentity);
        String query = evidenceQuery(request.path("priorArtifacts").path("evidence-collector").asText());
        var response = service.search(new SearchRequest(
                runId, stepId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 0, 1,
                snapshot, query, 20, 5, Map.of(), Set.of(), Instant.now().plusSeconds(60)));
        if (response.failure() != null) {
            throw new IllegalStateException(response.failure().errorCode());
        }
        List<Map<String, Object>> references = new ArrayList<>();
        for (var reference : response.value().references()) {
            references.add(Map.of(
                    "referenceId", reference.referenceId().toString(),
                    "chunkId", reference.chunkId().toString(),
                    "artifactId", reference.artifactId().value().toString(),
                    "location", reference.location(),
                    "contentSha256", reference.contentSha256(),
                    "rerankScore", reference.rerankScore(),
                    "rerankRank", reference.rerankRank(),
                    "text", chunkText(reference.artifactId(), reference.location())));
        }
        return JSON.writeValueAsString(Map.of(
                "schemaVersion", "1.0.0",
                "runId", runId.toString(),
                "outcome", response.value().outcome().name(),
                "embeddingApplied", response.value().outcome().name().equals("MATCH"),
                "rerankApplied", response.value().rerankApplied(),
                "knowledgeRevisionId", effective.knowledgeRevisionId().toString(),
                "modelRevisionId", effective.modelRevisionId().toString(),
                "references", references));
    }

    private UUID activeCollection() throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT collection_id FROM opspilot.knowledge_collection
                     WHERE active_knowledge_revision_id IS NOT NULL
                     ORDER BY collection_key LIMIT 1
                     """)) {
            try (var result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalStateException("KNOWLEDGE_ACTIVE_COLLECTION_MISSING");
                return result.getObject(1, UUID.class);
            }
        }
    }

    private String chunkText(ArtifactId artifactId, String location) {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT metadata_json ->> 'text' FROM opspilot.knowledge_chunk
                     WHERE content_artifact_id=? AND source_location=? AND searchable
                     """)) {
            statement.setObject(1, artifactId.value());
            statement.setString(2, location);
            try (var result = statement.executeQuery()) {
                if (!result.next() || result.getString(1) == null) {
                    throw new IllegalStateException("KNOWLEDGE_CHUNK_TEXT_MISSING");
                }
                return result.getString(1);
            }
        } catch (Exception failure) {
            throw failure instanceof RuntimeException runtime ? runtime
                    : new IllegalStateException("KNOWLEDGE_CHUNK_TEXT_READ_FAILED", failure);
        }
    }

    private static String evidenceQuery(String evidenceArtifact) throws Exception {
        JsonNode evidence = JSON.readTree(evidenceArtifact).path("evidence");
        List<String> claims = new ArrayList<>();
        evidence.forEach(item -> claims.add(item.path("claim").asText()));
        if (claims.isEmpty()) throw new IllegalStateException("KNOWLEDGE_QUERY_EVIDENCE_MISSING");
        return String.join("; ", claims);
    }

    private String required(String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + "_MISSING");
        return value;
    }
}
