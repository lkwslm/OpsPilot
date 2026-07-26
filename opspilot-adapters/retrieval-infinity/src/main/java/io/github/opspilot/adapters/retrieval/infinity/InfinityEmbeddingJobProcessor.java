package io.github.opspilot.adapters.retrieval.infinity;

import io.github.opspilot.adapters.retrieval.infinity.InfinityEmbeddingBatcher.Batch;
import io.github.opspilot.core.port.provider.EmbeddingPort;
import io.github.opspilot.core.port.provider.EmbeddingPort.EmbeddingRequest;
import io.github.opspilot.core.port.provider.ProviderContracts.ProviderFailure;
import io.github.opspilot.core.port.provider.ProviderContracts.ProviderIdentity;
import io.github.opspilot.core.port.repository.EmbeddingBatchCommitPort;
import io.github.opspilot.core.port.repository.EmbeddingBatchCommitPort.EmbeddingBatchCommit;
import io.github.opspilot.core.port.repository.EmbeddingBatchCommitPort.EmbeddingBatchFailure;
import io.github.opspilot.core.port.repository.EmbeddingBatchCommitPort.EmbeddingJobProgress;
import io.github.opspilot.core.port.repository.EmbeddingBatchCommitPort.EmbeddingVectorWrite;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Resumable embedding job that commits only fully validated provider batches. */
public final class InfinityEmbeddingJobProcessor {
    public record EmbeddingJobChunk(UUID chunkId, String normalizedText, String contentSha256) {
        public EmbeddingJobChunk {
            Objects.requireNonNull(chunkId, "chunkId");
            Objects.requireNonNull(normalizedText, "normalizedText");
            if (contentSha256 == null || !contentSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("contentSha256 must be lowercase SHA-256");
            }
            if (!contentSha256.equals(NormalizedTextHasher.sha256(normalizedText))) {
                throw new IllegalArgumentException("EMBEDDING_NORMALIZED_TEXT_HASH_MISMATCH");
            }
        }
    }

    public record EmbeddingJobRequest(
            UUID jobId, UUID modelRevisionId, Instant deadline, List<EmbeddingJobChunk> chunks) {
        public EmbeddingJobRequest {
            Objects.requireNonNull(jobId, "jobId");
            Objects.requireNonNull(modelRevisionId, "modelRevisionId");
            Objects.requireNonNull(deadline, "deadline");
            chunks = List.copyOf(chunks);
            if (chunks.isEmpty()) throw new IllegalArgumentException("embedding job chunks must not be empty");
        }
    }

    public record ProcessingResult(
            boolean complete, int completedChunks, int checkpointOrdinal, ProviderFailure failure) { }

    private final InfinityEmbeddingConfiguration configuration;
    private final InfinityEmbeddingBatcher batcher;
    private final EmbeddingPort provider;
    private final EmbeddingBatchCommitPort commits;
    private final InfinityVectorValidator vectors = new InfinityVectorValidator();

    public InfinityEmbeddingJobProcessor(
            InfinityEmbeddingConfiguration configuration,
            InfinityEmbeddingBatcher.TokenCounter tokenCounter,
            EmbeddingPort provider,
            EmbeddingBatchCommitPort commits) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.batcher = new InfinityEmbeddingBatcher(configuration.maxBatchItems(),
                configuration.providerMaxItems(), configuration.maxBatchTokens(), tokenCounter);
        this.provider = Objects.requireNonNull(provider, "provider");
        this.commits = Objects.requireNonNull(commits, "commits");
    }

    public ProcessingResult process(EmbeddingJobRequest request) {
        EmbeddingJobProgress progress = commits.load(request.jobId())
                .orElseThrow(() -> new IllegalArgumentException("EMBEDDING_JOB_NOT_FOUND"));
        validateProgress(request, progress);
        if (progress.completedChunks() == request.chunks().size()) {
            return new ProcessingResult(true, progress.completedChunks(), progress.checkpointOrdinal(), null);
        }
        List<EmbeddingJobChunk> remaining = request.chunks().subList(progress.completedChunks(),
                request.chunks().size());
        List<Batch> batches = batcher.plan(remaining.stream().map(EmbeddingJobChunk::normalizedText).toList());
        int completed = progress.completedChunks();
        int checkpoint = progress.checkpointOrdinal();
        int attempt = progress.attempts() + 1;
        ProviderIdentity identity = new ProviderIdentity(configuration.providerId(),
                configuration.servedModel(), configuration.revision());
        for (Batch batch : batches) {
            List<EmbeddingJobChunk> chunks = batch.inputs().stream()
                    .map(input -> remaining.get(input.globalIndex())).toList();
            float[][] resolved = new float[chunks.size()][];
            List<Integer> missingIndices = new ArrayList<>();
            for (int index = 0; index < chunks.size(); index++) {
                EmbeddingJobChunk chunk = chunks.get(index);
                var reusable = commits.findReusable(request.modelRevisionId(), chunk.contentSha256());
                if (reusable.isPresent()) {
                    resolved[index] = vectors.validateVector(reusable.get().vector(), configuration);
                } else {
                    missingIndices.add(index);
                }
            }
            if (!missingIndices.isEmpty()) {
                List<EmbeddingJobChunk> missing = missingIndices.stream().map(chunks::get).toList();
                var result = provider.embed(new EmbeddingRequest(identity, request.deadline(),
                        missing.stream().map(EmbeddingJobChunk::normalizedText).toList()));
                if (result.failure() != null) {
                    commits.recordFailure(new EmbeddingBatchFailure(request.jobId(), attempt, checkpoint,
                            chunks.stream().map(EmbeddingJobChunk::chunkId).toList(),
                            result.failure().errorCode(), result.failure().retryable()));
                    return new ProcessingResult(false, completed, checkpoint, result.failure());
                }
                if (result.value().size() != missing.size()) {
                    ProviderFailure failure = new ProviderFailure("EMBEDDING_COUNT_MISMATCH", false,
                            "Embedding result count did not match the current batch");
                    commits.recordFailure(new EmbeddingBatchFailure(request.jobId(), attempt, checkpoint,
                            chunks.stream().map(EmbeddingJobChunk::chunkId).toList(),
                            failure.errorCode(), false));
                    return new ProcessingResult(false, completed, checkpoint, failure);
                }
                for (int index = 0; index < missingIndices.size(); index++) {
                    resolved[missingIndices.get(index)] = result.value().get(index);
                }
            }
            if (java.util.Arrays.stream(resolved).anyMatch(Objects::isNull)) {
                ProviderFailure failure = new ProviderFailure("EMBEDDING_COUNT_MISMATCH", false,
                        "Embedding result count did not match the current batch");
                commits.recordFailure(new EmbeddingBatchFailure(request.jobId(), attempt, checkpoint,
                        chunks.stream().map(EmbeddingJobChunk::chunkId).toList(),
                        failure.errorCode(), false));
                return new ProcessingResult(false, completed, checkpoint, failure);
            }
            List<EmbeddingVectorWrite> writes = new ArrayList<>(chunks.size());
            for (int index = 0; index < chunks.size(); index++) {
                EmbeddingJobChunk chunk = chunks.get(index);
                writes.add(new EmbeddingVectorWrite(chunk.chunkId(), chunk.contentSha256(),
                        vectors.validateVector(resolved[index], configuration)));
            }
            completed += chunks.size();
            checkpoint++;
            commits.commit(new EmbeddingBatchCommit(request.jobId(), request.modelRevisionId(), checkpoint,
                    completed, request.chunks().size(), configuration.dimension(),
                    configuration.distanceMetric().name(), writes));
        }
        return new ProcessingResult(true, completed, checkpoint, null);
    }

    private static void validateProgress(EmbeddingJobRequest request, EmbeddingJobProgress progress) {
        if (!request.modelRevisionId().equals(progress.modelRevisionId())
                || progress.expectedChunks() != request.chunks().size()
                || progress.completedChunks() < 0
                || progress.completedChunks() > request.chunks().size()) {
            throw new IllegalArgumentException("EMBEDDING_JOB_IDENTITY_MISMATCH");
        }
    }
}
