package io.github.opspilot.core.application.knowledge;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Two-phase knowledge deletion that preserves any historically referenced revision. */
public final class KnowledgeRetentionService {
    private final KnowledgeLifecyclePort lifecycle;

    public KnowledgeRetentionService(KnowledgeLifecyclePort lifecycle) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    }

    public void delete(UUID documentId, Instant deletedAt) {
        lifecycle.markDeleted(documentId, deletedAt);
    }

    public CleanupResult clean(Instant olderThan) {
        int deleted = 0;
        int protectedCount = 0;
        for (CleanupCandidate candidate : lifecycle.cleanupCandidates(olderThan)) {
            if (lifecycle.hasArtifactRunReferenceOrEvidence(candidate)) {
                protectedCount++;
            } else {
                lifecycle.deletePhysical(candidate);
                deleted++;
            }
        }
        return new CleanupResult(deleted, protectedCount);
    }

    public interface KnowledgeLifecyclePort {
        void markDeleted(UUID documentId, Instant deletedAt);
        List<CleanupCandidate> cleanupCandidates(Instant olderThan);
        boolean hasArtifactRunReferenceOrEvidence(CleanupCandidate candidate);
        void deletePhysical(CleanupCandidate candidate);
    }

    public record CleanupCandidate(UUID documentId, UUID documentVersionId, UUID sourceArtifactId) {
    }

    public record CleanupResult(int deletedCount, int protectedCount) {
    }
}
