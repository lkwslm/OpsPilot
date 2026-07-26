package io.github.opspilot.core.port.provider;

import io.github.opspilot.core.port.provider.ProviderContracts.ProviderIdentity;
import io.github.opspilot.core.port.provider.ProviderContracts.ProviderResult;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public interface RerankPort {
    ProviderResult<List<RankedDocument>> rerank(RerankRequest request);

    record RerankRequest(
            ProviderIdentity identity, Instant deadline, String query, List<DocumentCandidate> candidates) {
        public RerankRequest { candidates = List.copyOf(candidates); }
    }

    record DocumentCandidate(String documentId, String text) {
    }

    record RankedDocument(
            String documentId,
            int originalIndex,
            double score,
            int rank,
            ProviderIdentity identity) {
        public RankedDocument {
            Objects.requireNonNull(documentId, "documentId");
        }

        /** Compatibility constructor for callers that do not yet expose rank provenance. */
        public RankedDocument(String documentId, double score) {
            this(documentId, -1, score, -1, null);
        }
    }
}
