package io.github.opspilot.core.port.repository;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Atomic persistence boundary for one fully validated embedding batch and its checkpoint. */
public interface EmbeddingBatchCommitPort {
    void commit(EmbeddingBatchCommit command);

    void recordFailure(EmbeddingBatchFailure failure);

    Optional<EmbeddingJobProgress> load(UUID jobId);

    Optional<ReusableEmbedding> findReusable(UUID modelRevisionId, String contentSha256);

    record ReusableEmbedding(UUID sourceChunkId, int dimension, float[] vector) {
        public ReusableEmbedding {
            Objects.requireNonNull(sourceChunkId, "sourceChunkId");
            if (dimension < 1 || vector == null || vector.length != dimension) {
                throw new IllegalArgumentException("reusable embedding dimension mismatch");
            }
            vector = vector.clone();
        }

        @Override public float[] vector() { return vector.clone(); }
    }

    record EmbeddingVectorWrite(UUID chunkId, String contentSha256, float[] vector) {
        public EmbeddingVectorWrite {
            Objects.requireNonNull(chunkId, "chunkId");
            if (contentSha256 == null || !contentSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("contentSha256 must be lowercase SHA-256");
            }
            Objects.requireNonNull(vector, "vector");
            vector = vector.clone();
        }

        @Override public float[] vector() { return vector.clone(); }
    }

    record EmbeddingBatchCommit(
            UUID jobId,
            UUID modelRevisionId,
            int checkpointOrdinal,
            int completedChunks,
            int expectedChunks,
            int dimension,
            String distanceMetric,
            List<EmbeddingVectorWrite> vectors) {
        public EmbeddingBatchCommit {
            Objects.requireNonNull(jobId, "jobId");
            Objects.requireNonNull(modelRevisionId, "modelRevisionId");
            if (checkpointOrdinal < 1 || completedChunks < 1 || expectedChunks < completedChunks
                    || dimension < 1) {
                throw new IllegalArgumentException("invalid embedding checkpoint values");
            }
            if (distanceMetric == null || distanceMetric.isBlank()) {
                throw new IllegalArgumentException("distanceMetric must not be blank");
            }
            vectors = List.copyOf(vectors);
            if (vectors.isEmpty()) {
                throw new IllegalArgumentException("embedding batch must not be empty");
            }
        }
    }

    record EmbeddingBatchFailure(
            UUID jobId,
            int attempt,
            int checkpointOrdinal,
            List<UUID> chunkIds,
            String errorCode,
            boolean retryable) {
        public EmbeddingBatchFailure {
            Objects.requireNonNull(jobId, "jobId");
            if (attempt < 1 || checkpointOrdinal < 0) {
                throw new IllegalArgumentException("attempt must be positive and checkpoint non-negative");
            }
            chunkIds = List.copyOf(chunkIds);
            if (chunkIds.isEmpty() || errorCode == null || errorCode.isBlank()) {
                throw new IllegalArgumentException("failure requires chunks and errorCode");
            }
        }
    }

    record EmbeddingJobProgress(
            UUID jobId,
            UUID modelRevisionId,
            int checkpointOrdinal,
            int completedChunks,
            int expectedChunks,
            int attempts,
            String status,
            String lastError) { }
}
