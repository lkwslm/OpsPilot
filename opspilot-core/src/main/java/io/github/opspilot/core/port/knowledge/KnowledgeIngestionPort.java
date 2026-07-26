package io.github.opspilot.core.port.knowledge;

import io.github.opspilot.core.domain.identity.DomainIds.ArtifactId;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Atomic persistence boundary for an immutable knowledge ingestion revision. */
public interface KnowledgeIngestionPort {
    Optional<AcceptedIngestion> findByIdempotencyKey(String idempotencyKey);

    AcceptedIngestion accept(IngestionRevision revision);

    record AcceptedIngestion(
            String idempotencyKey,
            String requestSha256,
            UUID documentId,
            UUID documentVersionId,
            UUID jobId) {
    }

    record IngestionRevision(
            AcceptedIngestion identity,
            UUID collectionId,
            String externalKey,
            String mediaType,
            ArtifactId sourceArtifactId,
            String contentSha256,
            String normalizationVersion,
            String chunkStrategyVersion,
            UUID modelRevisionId,
            int embeddingDimension,
            List<String> aclPrincipals,
            List<ChunkWrite> chunks) {
        public IngestionRevision {
            aclPrincipals = List.copyOf(aclPrincipals);
            chunks = List.copyOf(chunks);
        }
    }

    record ChunkWrite(
            UUID chunkId,
            int ordinal,
            ArtifactId artifactId,
            String location,
            String contentSha256,
            String normalizedText) {
    }
}
