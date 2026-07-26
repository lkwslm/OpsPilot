package io.github.opspilot.adapters.retrieval.infinity;

import io.github.opspilot.core.port.provider.ProviderContracts.ProviderIdentity;
import io.github.opspilot.core.port.provider.RerankPort.DocumentCandidate;
import io.github.opspilot.core.port.provider.RerankPort.RankedDocument;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Fail-closed validation and stable candidate reconstruction for rerank responses. */
final class InfinityRerankValidator {
    List<RankedDocument> validate(
            InfinityRerankDtos.Response response,
            List<DocumentCandidate> candidates,
            InfinityRerankConfiguration configuration) {
        validateCandidates(candidates);
        if (response == null || !configuration.servedModel().equals(response.model())) {
            throw new IllegalArgumentException("RERANK_MODEL_MISMATCH");
        }
        if (response.results() == null || response.results().size() != candidates.size()) {
            throw new IllegalArgumentException("RERANK_RESULT_COUNT_MISMATCH");
        }
        Set<Integer> indices = new HashSet<>();
        for (InfinityRerankDtos.Result result : response.results()) {
            if (result == null || result.index() < 0 || result.index() >= candidates.size()) {
                throw new IllegalArgumentException("RERANK_INDEX_OUT_OF_RANGE");
            }
            if (!indices.add(result.index())) {
                throw new IllegalArgumentException("RERANK_DUPLICATE_INDEX");
            }
            if (result.relevanceScore() == null || !Double.isFinite(result.relevanceScore())) {
                throw new IllegalArgumentException("RERANK_SCORE_NON_FINITE");
            }
        }
        if (indices.size() != candidates.size()) {
            throw new IllegalArgumentException("RERANK_INDEX_SET_INCOMPLETE");
        }

        ProviderIdentity identity = new ProviderIdentity(configuration.providerId(),
                configuration.servedModel(), configuration.revision());
        List<InfinityRerankDtos.Result> sorted = new ArrayList<>(response.results());
        sorted.sort(Comparator.comparingDouble(
                (InfinityRerankDtos.Result result) -> result.relevanceScore()).reversed()
                .thenComparingInt(InfinityRerankDtos.Result::index));
        List<RankedDocument> ranked = new ArrayList<>(sorted.size());
        for (int position = 0; position < sorted.size(); position++) {
            InfinityRerankDtos.Result result = sorted.get(position);
            ranked.add(new RankedDocument(candidates.get(result.index()).documentId(),
                    result.index(), result.relevanceScore(), position + 1, identity));
        }
        return List.copyOf(ranked);
    }

    void validateCandidates(List<DocumentCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException("RERANK_CANDIDATES_EMPTY");
        }
        Set<String> ids = new HashSet<>();
        for (DocumentCandidate candidate : candidates) {
            if (candidate == null || candidate.documentId() == null || candidate.documentId().isBlank()) {
                throw new IllegalArgumentException("RERANK_DOCUMENT_ID_MISSING");
            }
            if (!ids.add(candidate.documentId())) {
                throw new IllegalArgumentException("RERANK_DUPLICATE_DOCUMENT_ID");
            }
            if (candidate.text() == null) {
                throw new IllegalArgumentException("RERANK_DOCUMENT_TEXT_MISSING");
            }
        }
    }
}
