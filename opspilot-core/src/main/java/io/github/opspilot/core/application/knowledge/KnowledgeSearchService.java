package io.github.opspilot.core.application.knowledge;

import io.github.opspilot.core.domain.failure.ChainFailure;
import io.github.opspilot.core.domain.failure.ChainFailure.Category;
import io.github.opspilot.core.domain.failure.ChainFailure.CheckpointRef;
import io.github.opspilot.core.domain.failure.ChainFailure.FailureContext;
import io.github.opspilot.core.domain.identity.DomainIds.ArtifactId;
import io.github.opspilot.core.port.provider.EmbeddingPort;
import io.github.opspilot.core.port.provider.EmbeddingPort.EmbeddingRequest;
import io.github.opspilot.core.port.provider.ProviderContracts.ProviderIdentity;
import io.github.opspilot.core.port.provider.RerankPort;
import io.github.opspilot.core.port.provider.RerankPort.DocumentCandidate;
import io.github.opspilot.core.port.provider.RerankPort.RerankRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Mandatory embedding -> exact recall -> rerank knowledge chain with explicit empty outcomes. */
public final class KnowledgeSearchService {
    private static final Set<String> DISTANCE_METRICS = Set.of("COSINE", "INNER_PRODUCT", "L2");
    private static final Set<String> FILTER_KEYS = Set.of(
            "language", "service", "documentType", "tag", "relation");

    private final KnowledgeCatalogPort catalog;
    private final EmbeddingPort embedding;
    private final CandidateQueryPort candidates;
    private final RerankPort rerank;
    private final KnowledgeReferencePort references;
    private final KnowledgeSearchAuditPort audit;

    public KnowledgeSearchService(
            KnowledgeCatalogPort catalog,
            EmbeddingPort embedding,
            CandidateQueryPort candidates,
            RerankPort rerank,
            KnowledgeReferencePort references,
            KnowledgeSearchAuditPort audit) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.embedding = Objects.requireNonNull(embedding, "embedding");
        this.candidates = Objects.requireNonNull(candidates, "candidates");
        this.rerank = Objects.requireNonNull(rerank, "rerank");
        this.references = Objects.requireNonNull(references, "references");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    public SearchResponse search(SearchRequest request) {
        validate(request);
        if (!catalog.hasSearchableDocuments(request.snapshot(), request.aclPrincipals())) {
            return success(request, Outcome.KB_EMPTY, List.of(), false, 0, 0);
        }
        var embedded = embedding.embed(new EmbeddingRequest(request.snapshot().embeddingIdentity(),
                request.deadline(), List.of(request.query())));
        if (embedded.failure() != null) {
            return failure(request, "QUERY_EMBEDDING", embedded.failure().errorCode(),
                    embedded.failure().retryable(), Category.DEPENDENCY, "CATALOG_NON_EMPTY");
        }
        if (embedded.value().size() != 1
                || embedded.value().getFirst().length != request.snapshot().dimension()) {
            return failure(request, "QUERY_EMBEDDING", "QUERY_EMBEDDING_CONTRACT_FAILED",
                    false, Category.PROTOCOL, "CATALOG_NON_EMPTY");
        }

        List<KnowledgeCandidate> recalled;
        try {
            recalled = candidates.exactCandidates(new CandidateQuery(request.snapshot(),
                    embedded.value().getFirst(), request.candidateK(), request.filters(),
                    request.aclPrincipals()));
        } catch (CandidateQueryException problem) {
            return failure(request, "PGVECTOR_RECALL", problem.errorCode(), problem.retryable(),
                    problem.category(), "QUERY_EMBEDDED");
        }
        if (recalled.isEmpty()) {
            return success(request, Outcome.NO_MATCH, List.of(), false, 0, 0);
        }

        var reranked = rerank.rerank(new RerankRequest(request.snapshot().rerankIdentity(),
                request.deadline(), request.query(), recalled.stream().map(candidate ->
                new DocumentCandidate(candidate.chunkId().toString(), candidate.text())).toList()));
        if (reranked.failure() != null) {
            return failure(request, "RERANK", reranked.failure().errorCode(),
                    reranked.failure().retryable(), Category.DEPENDENCY, "CANDIDATES_RECALLED");
        }
        try {
            validateRerank(recalled, reranked.value(), request.snapshot().rerankIdentity());
        } catch (IllegalArgumentException problem) {
            return failure(request, "RERANK", problem.getMessage(), false,
                    Category.PROTOCOL, "CANDIDATES_RECALLED");
        }

        Map<UUID, KnowledgeCandidate> byChunk = new LinkedHashMap<>();
        recalled.forEach(candidate -> byChunk.put(candidate.chunkId(), candidate));
        List<KnowledgeReference> finalReferences = new ArrayList<>();
        int limit = Math.min(request.topK(), reranked.value().size());
        for (int index = 0; index < limit; index++) {
            var ranked = reranked.value().get(index);
            KnowledgeCandidate candidate = byChunk.get(UUID.fromString(ranked.documentId()));
            KnowledgeReference reference = new KnowledgeReference(UUID.randomUUID(), request.snapshot().collectionId(),
                    candidate.documentId(), candidate.documentVersionId(), candidate.chunkId(),
                    request.snapshot().knowledgeRevisionId(), request.snapshot().modelRevisionId(),
                    candidate.artifactId(), candidate.location(), candidate.contentSha256(),
                    filterSummary(request.filters()), candidate.distance(), ranked.score(), ranked.rank(),
                    ranked.identity());
            references.persist(request.runId(), reference);
            finalReferences.add(reference);
        }
        return success(request, Outcome.MATCH, finalReferences, true, recalled.size(),
                reranked.value().size());
    }

    private SearchResponse success(
            SearchRequest request, Outcome outcome, List<KnowledgeReference> refs,
            boolean rerankApplied, int candidateCount, int rerankCount) {
        SearchAudit summary = new SearchAudit(request.runId(), request.stepId(), request.invocationId(),
                request.snapshot(), filterSummary(request.filters()), request.candidateK(), request.topK(),
                request.attempt(), candidateCount, rerankCount, refs.stream().map(
                KnowledgeReference::referenceId).toList(), outcome);
        audit.record(summary);
        return new SearchResponse(new SearchSuccess(outcome, refs, rerankApplied, summary), null);
    }

    private SearchResponse failure(
            SearchRequest request, String stage, String code, boolean retryable,
            Category category, String upstream) {
        ChainFailure failure = new ChainFailure(UUID.randomUUID(), category, stableCode(code), retryable,
                request.correlationId(), new CheckpointRef(request.checkpointId(), request.checkpointVersion()),
                List.of(), new FailureContext(stage, request.attempt(), upstream),
                "Knowledge search stage failed");
        return new SearchResponse(null, failure);
    }

    private static void validate(SearchRequest request) {
        Objects.requireNonNull(request, "request");
        if (!DISTANCE_METRICS.contains(request.snapshot().distanceMetric())) {
            throw new IllegalArgumentException("KNOWLEDGE_DISTANCE_METRIC_NOT_ALLOWED");
        }
        if (!FILTER_KEYS.containsAll(request.filters().keySet())) {
            throw new IllegalArgumentException("KNOWLEDGE_FILTER_NOT_ALLOWED");
        }
        if (request.candidateK() < 1 || request.topK() < 1 || request.topK() > request.candidateK()) {
            throw new IllegalArgumentException("KNOWLEDGE_K_INVALID");
        }
    }

    private static void validateRerank(
            List<KnowledgeCandidate> candidates,
            List<RerankPort.RankedDocument> ranked,
            ProviderIdentity identity) {
        if (ranked == null || ranked.size() != candidates.size()) {
            throw new IllegalArgumentException("RERANK_RESULT_COUNT_MISMATCH");
        }
        Set<String> expected = new HashSet<>();
        candidates.forEach(candidate -> expected.add(candidate.chunkId().toString()));
        Set<String> actual = new HashSet<>();
        for (int index = 0; index < ranked.size(); index++) {
            var item = ranked.get(index);
            if (!actual.add(item.documentId()) || !expected.contains(item.documentId())) {
                throw new IllegalArgumentException("RERANK_DOCUMENT_ID_INVALID");
            }
            if (!Double.isFinite(item.score()) || item.rank() != index + 1
                    || !identity.equals(item.identity())) {
                throw new IllegalArgumentException("RERANK_IDENTITY_OR_RANK_INVALID");
            }
        }
    }

    private static String filterSummary(Map<String, String> filters) {
        return sha256(new java.util.TreeMap<>(filters).toString());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String stableCode(String code) {
        return code != null && code.matches("[A-Z][A-Z0-9_]{2,63}")
                ? code : "KNOWLEDGE_CHAIN_FAILED";
    }

    public interface KnowledgeCatalogPort {
        boolean hasSearchableDocuments(KnowledgeSnapshot snapshot, Set<String> aclPrincipals);
    }

    public interface CandidateQueryPort {
        List<KnowledgeCandidate> exactCandidates(CandidateQuery query);
    }

    public interface KnowledgeReferencePort {
        void persist(UUID runId, KnowledgeReference reference);
    }

    public interface KnowledgeSearchAuditPort {
        void record(SearchAudit audit);
    }

    public record KnowledgeSnapshot(
            UUID collectionId, UUID knowledgeRevisionId, UUID modelRevisionId,
            int dimension, String distanceMetric,
            ProviderIdentity embeddingIdentity, ProviderIdentity rerankIdentity) {
    }

    public record SearchRequest(
            UUID runId, UUID stepId, UUID invocationId, UUID correlationId,
            UUID checkpointId, long checkpointVersion, int attempt,
            KnowledgeSnapshot snapshot, String query, int candidateK, int topK,
            Map<String, String> filters, Set<String> aclPrincipals, Instant deadline) {
        public SearchRequest {
            filters = Map.copyOf(filters);
            aclPrincipals = Set.copyOf(aclPrincipals);
        }
    }

    public record CandidateQuery(
            KnowledgeSnapshot snapshot, float[] queryVector, int candidateK,
            Map<String, String> filters, Set<String> aclPrincipals) {
        public CandidateQuery { queryVector = queryVector.clone(); }
        @Override public float[] queryVector() { return queryVector.clone(); }
    }

    public record KnowledgeCandidate(
            UUID documentId, UUID documentVersionId, UUID chunkId, ArtifactId artifactId,
            String location, String contentSha256, String text, double distance) {
    }

    public record KnowledgeReference(
            UUID referenceId, UUID collectionId, UUID documentId, UUID documentVersionId,
            UUID chunkId, UUID knowledgeRevisionId, UUID modelRevisionId, ArtifactId artifactId,
            String location, String contentSha256, String filterSummarySha256, double vectorDistance,
            double rerankScore, int rerankRank, ProviderIdentity rerankIdentity) {
    }

    public record SearchSuccess(
            Outcome outcome, List<KnowledgeReference> references,
            boolean rerankApplied, SearchAudit audit) {
        public SearchSuccess { references = List.copyOf(references); }
    }

    public record SearchResponse(SearchSuccess value, ChainFailure failure) {
        public SearchResponse {
            if ((value == null) == (failure == null)) {
                throw new IllegalArgumentException("search response needs exactly one outcome");
            }
        }
    }

    public record SearchAudit(
            UUID runId, UUID stepId, UUID invocationId, KnowledgeSnapshot snapshot,
            String filterSummarySha256, int candidateK, int topK, int attempt,
            int candidateCount, int rerankCount, List<UUID> referenceIds, Outcome outcome) {
        public SearchAudit { referenceIds = List.copyOf(referenceIds); }
    }

    public enum Outcome { KB_EMPTY, NO_MATCH, MATCH }

    public static final class CandidateQueryException extends RuntimeException {
        private final String errorCode;
        private final boolean retryable;
        private final Category category;
        public CandidateQueryException(String errorCode, boolean retryable, Category category) {
            super(errorCode);
            this.errorCode = errorCode;
            this.retryable = retryable;
            this.category = category;
        }
        public String errorCode() { return errorCode; }
        public boolean retryable() { return retryable; }
        public Category category() { return category; }
    }
}
