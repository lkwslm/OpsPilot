package io.github.opspilot.adapters.retrieval.infinity;

import io.github.opspilot.adapters.retrieval.infinity.InfinityEmbeddingConfiguration.DistanceMetric;
import io.github.opspilot.adapters.retrieval.infinity.InfinityEmbeddingConfiguration.Normalization;
import io.github.opspilot.adapters.retrieval.infinity.InfinityEmbeddingJobProcessor.EmbeddingJobChunk;
import io.github.opspilot.adapters.retrieval.infinity.InfinityEmbeddingJobProcessor.EmbeddingJobRequest;
import io.github.opspilot.core.port.provider.EmbeddingPort;
import io.github.opspilot.core.port.provider.ProviderContracts.ProviderFailure;
import io.github.opspilot.core.port.provider.ProviderContracts.ProviderResult;
import io.github.opspilot.core.port.provider.ProviderContracts.ProviderUsage;
import io.github.opspilot.core.port.repository.EmbeddingBatchCommitPort;
import io.github.opspilot.core.port.repository.EmbeddingBatchCommitPort.ReusableEmbedding;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InfinityEmbeddingJobProcessorTest {
    private static final Instant DEADLINE = Instant.parse("2026-07-26T00:01:00Z");

    @Test
    void providerFailureCheckpointsTheWholeBatchWithoutWrites() {
        Fixture fixture = new Fixture(0, request -> new ProviderResult<>(null, null,
                new ProviderFailure("INFINITY_HTTP_503", true, "unavailable")));

        var result = fixture.processor().process(fixture.request(3));

        assertFalse(result.complete());
        assertEquals(0, fixture.store.vectors.size());
        assertEquals(0, fixture.store.progress.completedChunks());
        assertEquals("INFINITY_HTTP_503", fixture.store.progress.lastError());
    }

    @Test
    void countMismatchCannotCommitAPartialBatch() {
        Fixture fixture = new Fixture(0, request -> success(List.of(new float[]{1, 0})));

        var result = fixture.processor().process(fixture.request(3));

        assertFalse(result.complete());
        assertEquals("EMBEDDING_COUNT_MISMATCH", result.failure().errorCode());
        assertTrue(fixture.store.vectors.isEmpty());
    }

    @Test
    void processRecoverySkipsCommittedChunksAndFinishesFromCheckpoint() {
        ArrayList<List<String>> calls = new ArrayList<>();
        Fixture fixture = new Fixture(2, request -> {
            calls.add(request.inputs());
            return success(request.inputs().stream().map(ignored -> new float[]{1, 0}).toList());
        });

        var result = fixture.processor().process(fixture.request(3));

        assertTrue(result.complete());
        assertEquals(List.of(List.of("text-2")), calls);
        assertEquals(3, fixture.store.progress.completedChunks());
        assertEquals("COMPLETED", fixture.store.progress.status());
    }

    @Test
    void sameRevisionReuseAvoidsProviderButWritesIndependentChunkRows() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        Fixture fixture = new Fixture(0, request -> {
            calls.incrementAndGet();
            return success(request.inputs().stream().map(ignored -> new float[]{1, 0}).toList());
        });
        for (int index = 0; index < 3; index++) {
            fixture.store.reusable.put(new CacheKey(fixture.revisionId,
                            NormalizedTextHasher.sha256("text-" + index)),
                    new ReusableEmbedding(UUID.randomUUID(), 2, new float[]{1, 0}));
        }

        var result = fixture.processor().process(fixture.request(3));

        assertTrue(result.complete());
        assertEquals(0, calls.get());
        assertEquals(3, fixture.store.vectors.size());
    }

    @Test
    void crossRevisionCacheEntryForcesRealProviderComputation() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        Fixture fixture = new Fixture(0, request -> {
            calls.incrementAndGet();
            return success(request.inputs().stream().map(ignored -> new float[]{1, 0}).toList());
        });
        fixture.store.reusable.put(new CacheKey(UUID.randomUUID(), NormalizedTextHasher.sha256("text-0")),
                new ReusableEmbedding(UUID.randomUUID(), 2, new float[]{1, 0}));

        assertTrue(fixture.processor().process(fixture.request(3)).complete());
        assertEquals(2, calls.get());
    }

    private static ProviderResult<List<float[]>> success(List<float[]> vectors) {
        return new ProviderResult<>(vectors, new ProviderUsage(vectors.size(), 0, null), null);
    }

    private static final class Fixture {
        private final UUID jobId = UUID.randomUUID();
        private final UUID revisionId = UUID.randomUUID();
        private final Store store;
        private final EmbeddingPort provider;

        private Fixture(int completed, EmbeddingPort provider) {
            this.provider = provider;
            this.store = new Store(new EmbeddingBatchCommitPort.EmbeddingJobProgress(jobId, revisionId,
                    completed == 0 ? 0 : 1, completed, 3, completed == 0 ? 0 : 1,
                    completed == 3 ? "COMPLETED" : "RUNNING", null));
        }

        private InfinityEmbeddingJobProcessor processor() {
            var config = new InfinityEmbeddingConfiguration("infinity", URI.create("http://127.0.0.1:7997"),
                    "embedding", "revision", 2, Normalization.NONE, DistanceMetric.COSINE,
                    2, 2, 100);
            return new InfinityEmbeddingJobProcessor(config, String::length, provider, store);
        }

        private EmbeddingJobRequest request(int chunks) {
            ArrayList<EmbeddingJobChunk> values = new ArrayList<>();
            for (int index = 0; index < chunks; index++) {
                values.add(new EmbeddingJobChunk(UUID.nameUUIDFromBytes(("chunk-" + index).getBytes()),
                        "text-" + index, NormalizedTextHasher.sha256("text-" + index)));
            }
            return new EmbeddingJobRequest(jobId, revisionId, DEADLINE, values);
        }
    }

    private static final class Store implements EmbeddingBatchCommitPort {
        private EmbeddingJobProgress progress;
        private final Map<UUID, float[]> vectors = new LinkedHashMap<>();
        private final Map<CacheKey, ReusableEmbedding> reusable = new LinkedHashMap<>();

        private Store(EmbeddingJobProgress progress) { this.progress = progress; }

        @Override
        public void commit(EmbeddingBatchCommit command) {
            Map<UUID, float[]> staged = new LinkedHashMap<>();
            command.vectors().forEach(vector -> staged.put(vector.chunkId(), vector.vector()));
            vectors.putAll(staged);
            progress = new EmbeddingJobProgress(command.jobId(), command.modelRevisionId(),
                    command.checkpointOrdinal(), command.completedChunks(), command.expectedChunks(),
                    progress.attempts() + 1,
                    command.completedChunks() == command.expectedChunks() ? "COMPLETED" : "RUNNING", null);
        }

        @Override
        public void recordFailure(EmbeddingBatchFailure failure) {
            progress = new EmbeddingJobProgress(progress.jobId(), progress.modelRevisionId(),
                    progress.checkpointOrdinal(), progress.completedChunks(), progress.expectedChunks(),
                    failure.attempt(), failure.retryable() ? "RECOVERING" : "FAILED", failure.errorCode());
        }

        @Override public Optional<EmbeddingJobProgress> load(UUID jobId) {
            return progress.jobId().equals(jobId) ? Optional.of(progress) : Optional.empty();
        }

        @Override
        public Optional<ReusableEmbedding> findReusable(UUID modelRevisionId, String contentSha256) {
            return Optional.ofNullable(reusable.get(new CacheKey(modelRevisionId, contentSha256)));
        }
    }

    private record CacheKey(UUID revisionId, String contentSha256) { }
}
