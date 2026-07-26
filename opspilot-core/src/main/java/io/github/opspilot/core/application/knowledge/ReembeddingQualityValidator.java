package io.github.opspilot.core.application.knowledge;

import io.github.opspilot.core.port.provider.ProviderContracts.ProviderIdentity;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Fail-closed gates for a side-by-side immutable re-embedding revision. */
public final class ReembeddingQualityValidator {
    public ValidationReport validate(RevisionCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate");
        List<String> failures = new ArrayList<>();
        if (candidate.completedChunks() != candidate.expectedChunks()
                || candidate.coveredChunkIds().size() != candidate.expectedChunks()) {
            failures.add("REEMBEDDING_COVERAGE_INCOMPLETE");
        }
        if (candidate.documentCount() < 1 || candidate.chunkCount() != candidate.expectedChunks()) {
            failures.add("REEMBEDDING_COUNT_MISMATCH");
        }
        if (candidate.aclCompleteCount() != candidate.chunkCount()) {
            failures.add("REEMBEDDING_ACL_INCOMPLETE");
        }
        if (!candidate.expectedIdentity().equals(candidate.actualIdentity())) {
            failures.add("REEMBEDDING_IDENTITY_MISMATCH");
        }
        for (float[] vector : candidate.vectors()) {
            if (vector == null || vector.length != candidate.dimension()) {
                failures.add("REEMBEDDING_DIMENSION_MISMATCH");
                break;
            }
            for (float value : vector) {
                if (!Float.isFinite(value)) {
                    failures.add("REEMBEDDING_NON_FINITE");
                    break;
                }
            }
        }
        if (candidate.sampleQualityAfter() < candidate.sampleQualityBefore()) {
            failures.add("REEMBEDDING_QUALITY_REGRESSION");
        }
        return new ValidationReport(candidate.knowledgeRevisionId(), failures.isEmpty(), failures);
    }

    public record RevisionCandidate(
            UUID knowledgeRevisionId,
            int documentCount,
            int chunkCount,
            int expectedChunks,
            int completedChunks,
            Set<UUID> coveredChunkIds,
            int aclCompleteCount,
            int dimension,
            List<float[]> vectors,
            ProviderIdentity expectedIdentity,
            ProviderIdentity actualIdentity,
            double sampleQualityBefore,
            double sampleQualityAfter) {
        public RevisionCandidate {
            coveredChunkIds = Set.copyOf(coveredChunkIds);
            vectors = vectors.stream().map(vector -> vector == null ? null : vector.clone()).toList();
        }
    }

    public record ValidationReport(UUID knowledgeRevisionId, boolean passed, List<String> failures) {
        public ValidationReport { failures = List.copyOf(failures); }
    }
}
