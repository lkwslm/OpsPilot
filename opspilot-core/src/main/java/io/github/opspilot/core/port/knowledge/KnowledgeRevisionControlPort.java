package io.github.opspilot.core.port.knowledge;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Atomic persistence boundary for preparing, activating and restoring immutable Knowledge revisions. */
public interface KnowledgeRevisionControlPort {
    Optional<RevisionStatus> findPrepared(UUID collectionId, UUID revisionId, String manifestSha256);

    Optional<UUID> findActiveRevision(UUID collectionId);

    Optional<ActivationReceipt> findReceipt(UUID receiptId);

    RevisionStatus prepare(PreparedRevision revision);

    ActivationReceipt activate(
            String operationId,
            String principalId,
            UUID collectionId,
            UUID targetRevisionId,
            UUID expectedActiveRevisionId,
            Instant receiptExpiresAt,
            Instant activatedAt);

    ActivationReceipt restore(
            String operationId,
            String principalId,
            ActivationReceipt receipt,
            Instant restoredAt);

    record PreparedRevision(
            String operationId,
            String principalId,
            UUID collectionId,
            UUID revisionId,
            String revisionKey,
            String manifestSha256,
            UUID modelRevisionId,
            String modelRevisionKey,
            int embeddingDimension,
            String distanceMetric,
            Instant expiresAt,
            List<PreparedChunk> chunks) {
        public PreparedRevision {
            chunks = List.copyOf(chunks);
        }
    }

    record PreparedChunk(
            UUID chunkId,
            String externalKey,
            String text,
            String contentSha256,
            Map<String, String> metadata,
            List<String> aclPrincipals,
            float[] embedding) {
        public PreparedChunk {
            metadata = Map.copyOf(new LinkedHashMap<>(metadata));
            aclPrincipals = List.copyOf(aclPrincipals);
            embedding = embedding.clone();
        }

        @Override
        public float[] embedding() {
            return embedding.clone();
        }
    }

    record RevisionStatus(
            UUID collectionId,
            UUID revisionId,
            String revisionKey,
            String manifestSha256,
            String status,
            int chunkCount,
            UUID modelRevisionId,
            String modelRevisionKey,
            Instant expiresAt) {
    }

    record ActivationReceipt(
            UUID receiptId,
            UUID collectionId,
            UUID previousRevisionId,
            UUID activatedRevisionId,
            Instant expiresAt,
            Instant activatedAt,
            Instant restoredAt) {
    }
}
