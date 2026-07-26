package io.github.opspilot.adapters.retrieval.infinity;

import io.github.opspilot.adapters.retrieval.infinity.InfinityEmbeddingConfiguration.DistanceMetric;
import io.github.opspilot.adapters.retrieval.infinity.InfinityEmbeddingConfiguration.Normalization;
import io.github.opspilot.core.port.provider.EmbeddingPort;
import io.github.opspilot.core.port.provider.EmbeddingPort.EmbeddingRequest;
import io.github.opspilot.core.port.provider.ProviderContracts.ProviderIdentity;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Query entry point that validates the Run's document revision before provider or database access. */
public final class RevisionLockedKnowledgeQuery {
    public record QueryRevisionContract(
            String providerId,
            String servedModel,
            String revision,
            UUID modelRevisionId,
            String preprocessingVersion,
            int dimension,
            Normalization normalization,
            DistanceMetric distanceMetric) {
        public QueryRevisionContract {
            Objects.requireNonNull(providerId, "providerId");
            Objects.requireNonNull(servedModel, "servedModel");
            Objects.requireNonNull(revision, "revision");
            Objects.requireNonNull(modelRevisionId, "modelRevisionId");
            Objects.requireNonNull(preprocessingVersion, "preprocessingVersion");
            Objects.requireNonNull(normalization, "normalization");
            Objects.requireNonNull(distanceMetric, "distanceMetric");
            if (dimension < 1) throw new IllegalArgumentException("dimension must be positive");
        }
    }

    public record KnowledgeQueryRequest(
            String query,
            QueryRevisionContract runActiveDocumentRevision,
            QueryRevisionContract queryEmbeddingProfile,
            Instant deadline) {
        public KnowledgeQueryRequest {
            Objects.requireNonNull(query, "query");
            Objects.requireNonNull(runActiveDocumentRevision, "runActiveDocumentRevision");
            Objects.requireNonNull(queryEmbeddingProfile, "queryEmbeddingProfile");
            Objects.requireNonNull(deadline, "deadline");
        }
    }

    public record VectorSearchRequest(
            UUID modelRevisionId, int dimension, DistanceMetric distanceMetric, float[] queryVector) {
        public VectorSearchRequest {
            queryVector = queryVector.clone();
        }
        @Override public float[] queryVector() { return queryVector.clone(); }
    }

    @FunctionalInterface
    public interface VectorSearch {
        List<UUID> search(VectorSearchRequest request);
    }

    private final InfinityEmbeddingConfiguration configuration;
    private final EmbeddingPort provider;
    private final VectorSearch search;
    private final InfinityVectorValidator vectors = new InfinityVectorValidator();

    public RevisionLockedKnowledgeQuery(
            InfinityEmbeddingConfiguration configuration, EmbeddingPort provider, VectorSearch search) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.search = Objects.requireNonNull(search, "search");
    }

    public List<UUID> execute(KnowledgeQueryRequest request) {
        QueryRevisionContract document = request.runActiveDocumentRevision();
        if (!document.equals(request.queryEmbeddingProfile()) || !matchesConfiguration(document)
                || !NormalizedTextHasher.NORMALIZATION_VERSION.equals(document.preprocessingVersion())) {
            throw new QueryContractException("EMBEDDING_QUERY_REVISION_MISMATCH");
        }
        ProviderIdentity identity = new ProviderIdentity(document.providerId(), document.servedModel(),
                document.revision());
        String normalizedQuery = NormalizedTextHasher.normalize(request.query());
        var result = provider.embed(new EmbeddingRequest(identity, request.deadline(), List.of(normalizedQuery)));
        if (result.failure() != null) {
            throw new QueryContractException(result.failure().errorCode());
        }
        if (result.value().size() != 1) {
            throw new QueryContractException("EMBEDDING_COUNT_MISMATCH");
        }
        float[] vector = vectors.validateVector(result.value().getFirst(), configuration);
        return List.copyOf(search.search(new VectorSearchRequest(document.modelRevisionId(),
                document.dimension(), document.distanceMetric(), vector)));
    }

    private boolean matchesConfiguration(QueryRevisionContract contract) {
        return configuration.providerId().equals(contract.providerId())
                && configuration.servedModel().equals(contract.servedModel())
                && configuration.revision().equals(contract.revision())
                && configuration.dimension() == contract.dimension()
                && configuration.normalization() == contract.normalization()
                && configuration.distanceMetric() == contract.distanceMetric();
    }

    public static final class QueryContractException extends IllegalArgumentException {
        public QueryContractException(String code) { super(code); }
    }
}
