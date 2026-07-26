package io.github.opspilot.adapters.retrieval.infinity;

import io.github.opspilot.adapters.retrieval.infinity.InfinityEmbeddingConfiguration.DistanceMetric;
import io.github.opspilot.adapters.retrieval.infinity.InfinityEmbeddingConfiguration.Normalization;
import io.github.opspilot.adapters.retrieval.infinity.RevisionLockedKnowledgeQuery.KnowledgeQueryRequest;
import io.github.opspilot.adapters.retrieval.infinity.RevisionLockedKnowledgeQuery.QueryContractException;
import io.github.opspilot.adapters.retrieval.infinity.RevisionLockedKnowledgeQuery.QueryRevisionContract;
import io.github.opspilot.core.port.provider.EmbeddingPort;
import io.github.opspilot.core.port.provider.ProviderContracts.ProviderResult;
import io.github.opspilot.core.port.provider.ProviderContracts.ProviderUsage;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RevisionLockedKnowledgeQueryTest {
    private final InfinityEmbeddingConfiguration configuration = new InfinityEmbeddingConfiguration(
            "infinity", URI.create("http://127.0.0.1:7997"), "embedding", "revision-a", 2,
            Normalization.L2_UNIT, DistanceMetric.COSINE, 4, 4, 512);

    @Test
    void revisionMismatchFailsBeforeProviderAndDatabaseWithoutFailover() {
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger databaseCalls = new AtomicInteger();
        EmbeddingPort provider = request -> {
            providerCalls.incrementAndGet();
            throw new AssertionError("provider must not be called");
        };
        RevisionLockedKnowledgeQuery query = new RevisionLockedKnowledgeQuery(configuration, provider, request -> {
            databaseCalls.incrementAndGet();
            throw new AssertionError("database must not be called");
        });
        QueryRevisionContract active = contract("revision-a", UUID.randomUUID());
        QueryRevisionContract mismatched = contract("revision-b", UUID.randomUUID());

        assertEquals("EMBEDDING_QUERY_REVISION_MISMATCH", assertThrows(QueryContractException.class,
                () -> query.execute(new KnowledgeQueryRequest("故障查询", active, mismatched,
                        Instant.now().plusSeconds(2)))).getMessage());
        assertEquals(0, providerCalls.get());
        assertEquals(0, databaseCalls.get());
    }

    @Test
    void exactRunContractEmbedsThenQueriesWithTheSameRevisionShapeAndMetric() {
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger databaseCalls = new AtomicInteger();
        UUID modelRevisionId = UUID.randomUUID();
        UUID hit = UUID.randomUUID();
        EmbeddingPort provider = request -> {
            providerCalls.incrementAndGet();
            assertEquals("revision-a", request.identity().revision());
            return new ProviderResult<>(List.of(new float[]{1, 0}), new ProviderUsage(3, 0, null), null);
        };
        RevisionLockedKnowledgeQuery query = new RevisionLockedKnowledgeQuery(configuration, provider, request -> {
            databaseCalls.incrementAndGet();
            assertEquals(modelRevisionId, request.modelRevisionId());
            assertEquals(2, request.dimension());
            assertEquals(DistanceMetric.COSINE, request.distanceMetric());
            return List.of(hit);
        });
        QueryRevisionContract contract = contract("revision-a", modelRevisionId);

        assertEquals(List.of(hit), query.execute(new KnowledgeQueryRequest(
                "  Ａ故障  ", contract, contract, Instant.now().plusSeconds(2))));
        assertEquals(1, providerCalls.get());
        assertEquals(1, databaseCalls.get());
    }

    private static QueryRevisionContract contract(String revision, UUID modelRevisionId) {
        return new QueryRevisionContract("infinity", "embedding", revision, modelRevisionId,
                NormalizedTextHasher.NORMALIZATION_VERSION, 2, Normalization.L2_UNIT, DistanceMetric.COSINE);
    }
}
