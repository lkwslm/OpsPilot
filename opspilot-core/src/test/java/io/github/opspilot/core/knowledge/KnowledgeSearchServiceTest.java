package io.github.opspilot.core.knowledge;

import io.github.opspilot.core.application.knowledge.KnowledgeSearchService;
import io.github.opspilot.core.application.knowledge.KnowledgeSearchService.CandidateQueryException;
import io.github.opspilot.core.application.knowledge.KnowledgeSearchService.KnowledgeCandidate;
import io.github.opspilot.core.application.knowledge.KnowledgeSearchService.KnowledgeSnapshot;
import io.github.opspilot.core.application.knowledge.KnowledgeSearchService.Outcome;
import io.github.opspilot.core.application.knowledge.KnowledgeSearchService.SearchRequest;
import io.github.opspilot.core.application.knowledge.KnowledgeReferenceEvidenceMapper;
import io.github.opspilot.core.application.knowledge.KnowledgeReferenceEvidenceMapper.ReferenceVerificationException;
import io.github.opspilot.core.application.knowledge.ReembeddingQualityValidator;
import io.github.opspilot.core.application.knowledge.ReembeddingQualityValidator.RevisionCandidate;
import io.github.opspilot.core.domain.failure.ChainFailure.Category;
import io.github.opspilot.core.domain.identity.DomainIds.ArtifactId;
import io.github.opspilot.core.port.provider.ProviderContracts.ProviderFailure;
import io.github.opspilot.core.port.provider.ProviderContracts.ProviderIdentity;
import io.github.opspilot.core.port.provider.ProviderContracts.ProviderResult;
import io.github.opspilot.core.port.provider.ProviderContracts.ProviderUsage;
import io.github.opspilot.core.port.provider.RerankPort.RankedDocument;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeSearchServiceTest {
    private static final ProviderIdentity EMBED = new ProviderIdentity("infinity", "embed", "e1");
    private static final ProviderIdentity RERANK = new ProviderIdentity("infinity", "rerank", "r1");

    @Test
    void emptyKnowledgeBaseCompletesWithoutCallingAnyDownstream() {
        Fixture fixture = new Fixture(false, List.of());

        var response = fixture.service.search(request("COSINE"));

        assertEquals(Outcome.KB_EMPTY, response.value().outcome());
        assertFalse(response.value().rerankApplied());
        assertTrue(response.value().references().isEmpty());
        assertEquals(List.of(0, 0, 0), fixture.calls());
    }

    @Test
    void zeroCandidatesIsNoMatchAfterEmbeddingAndDatabaseButWithoutRerank() {
        Fixture fixture = new Fixture(true, List.of());

        var response = fixture.service.search(request("COSINE"));

        assertEquals(Outcome.NO_MATCH, response.value().outcome());
        assertFalse(response.value().rerankApplied());
        assertEquals(List.of(1, 1, 0), fixture.calls());
    }

    @Test
    void nonEmptyCandidatesMustRerankAndCreateCompleteBodyFreeReferences() {
        List<KnowledgeCandidate> candidates = candidates();
        Fixture fixture = new Fixture(true, candidates);

        var response = fixture.service.search(request("COSINE"));

        assertEquals(Outcome.MATCH, response.value().outcome());
        assertTrue(response.value().rerankApplied());
        assertEquals(2, response.value().references().size());
        assertEquals(candidates.get(2).chunkId(), response.value().references().getFirst().chunkId());
        assertEquals(RERANK, response.value().references().getFirst().rerankIdentity());
        assertEquals(64, response.value().references().getFirst().filterSummarySha256().length());
        assertEquals(List.of(1, 1, 1), fixture.calls());
        assertEquals(2, fixture.persisted.size());
    }

    @Test
    void embeddingDatabaseAndRerankFailuresNeverBecomeEmptyOrFallbackResults() {
        Fixture embeddingFailure = new Fixture(true, candidates());
        embeddingFailure.embeddingFailure = new ProviderFailure("EMBEDDING_TIMEOUT", true, "safe");
        var first = embeddingFailure.service.search(request("COSINE"));
        assertEquals("EMBEDDING_TIMEOUT", first.failure().errorCode());
        assertEquals("QUERY_EMBEDDING", first.failure().context().failedStage());
        assertNull(first.value());

        Fixture databaseFailure = new Fixture(true, candidates());
        databaseFailure.databaseFailure = new CandidateQueryException(
                "PGVECTOR_TIMEOUT", true, Category.PERSISTENCE);
        var second = databaseFailure.service.search(request("COSINE"));
        assertEquals("PGVECTOR_TIMEOUT", second.failure().errorCode());
        assertEquals(List.of(1, 1, 0), databaseFailure.calls());

        Fixture rerankFailure = new Fixture(true, candidates());
        rerankFailure.rerankFailure = new ProviderFailure("RERANK_TIMEOUT", true, "safe");
        var third = rerankFailure.service.search(request("COSINE"));
        assertEquals("RERANK_TIMEOUT", third.failure().errorCode());
        assertEquals("RERANK", third.failure().context().failedStage());
        assertFalse(third.failure().context().upstreamState().contains("EMPTY"));
        assertTrue(rerankFailure.persisted.isEmpty());
    }

    @Test
    void incompleteOrWrongIdentityRerankResponseFailsWithoutReferences() {
        Fixture fixture = new Fixture(true, candidates());
        fixture.invalidRerank = true;

        var response = fixture.service.search(request("COSINE"));

        assertEquals("RERANK_RESULT_COUNT_MISMATCH", response.failure().errorCode());
        assertNull(response.value());
        assertTrue(fixture.persisted.isEmpty());
    }

    @Test
    void operatorAndFilterInjectionAreRejectedBeforeCatalogOrDatabase() {
        Fixture fixture = new Fixture(true, candidates());

        assertEquals("KNOWLEDGE_DISTANCE_METRIC_NOT_ALLOWED", assertThrows(
                IllegalArgumentException.class, () -> fixture.service.search(request("<=>; DROP"))).getMessage());
        SearchRequest invalidFilter = copy(request("COSINE"), Map.of("x' OR true --", "yes"));
        assertEquals("KNOWLEDGE_FILTER_NOT_ALLOWED", assertThrows(
                IllegalArgumentException.class, () -> fixture.service.search(invalidFilter)).getMessage());
        assertEquals(List.of(0, 0, 0), fixture.calls());
    }

    @Test
    void knowledgeAssertionNeedsVerifiedReferenceBeforeItCanEnterEvidenceNormalizer() {
        Fixture fixture = new Fixture(true, candidates());
        var reference = fixture.service.search(request("COSINE")).value().references().getFirst();
        KnowledgeReferenceEvidenceMapper denied = new KnowledgeReferenceEvidenceMapper(
                (run, candidate, acl) -> false);
        assertEquals("KNOWLEDGE_REFERENCE_VERIFICATION_FAILED", assertThrows(
                ReferenceVerificationException.class,
                () -> denied.verifiedResult(UUID.randomUUID(), reference,
                        Set.of("role:knowledge"), "断言")).getMessage());

        KnowledgeReferenceEvidenceMapper allowed = new KnowledgeReferenceEvidenceMapper(
                (run, candidate, acl) -> candidate.equals(reference) && acl.contains("role:knowledge"));
        var normalizedInput = allowed.verifiedResult(UUID.randomUUID(), reference,
                Set.of("role:knowledge"), "连接池可能存在泄漏");
        assertEquals(reference.referenceId().toString(), normalizedInput.resultId());
        assertEquals(List.of(reference.artifactId()), normalizedInput.artifactIds());
    }

    @Test
    void sideBySideRevisionMustPassCoverageAclVectorIdentityAndQualityGates() {
        ReembeddingQualityValidator validator = new ReembeddingQualityValidator();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        RevisionCandidate valid = new RevisionCandidate(UUID.randomUUID(), 1, 2, 2, 2,
                Set.of(first, second), 2, 3,
                List.of(new float[]{1, 0, 0}, new float[]{0, 1, 0}), EMBED, EMBED, .7, .8);
        assertTrue(validator.validate(valid).passed());

        RevisionCandidate invalid = new RevisionCandidate(UUID.randomUUID(), 1, 2, 2, 1,
                Set.of(first), 1, 3,
                List.of(new float[]{1, 0}, new float[]{Float.NaN, 1, 0}),
                EMBED, new ProviderIdentity("infinity", "embed", "other"), .8, .7);
        var failures = validator.validate(invalid).failures();
        assertTrue(failures.contains("REEMBEDDING_COVERAGE_INCOMPLETE"));
        assertTrue(failures.contains("REEMBEDDING_ACL_INCOMPLETE"));
        assertTrue(failures.contains("REEMBEDDING_IDENTITY_MISMATCH"));
        assertTrue(failures.contains("REEMBEDDING_DIMENSION_MISMATCH"));
        assertTrue(failures.contains("REEMBEDDING_QUALITY_REGRESSION"));
    }

    private static SearchRequest request(String metric) {
        return new SearchRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 7, 2, new KnowledgeSnapshot(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 3, metric, EMBED, RERANK), "查询", 10, 2,
                Map.of("service", "payment"), Set.of("role:knowledge"),
                Instant.parse("2026-07-26T12:00:00Z"));
    }

    private static SearchRequest copy(SearchRequest request, Map<String, String> filters) {
        return new SearchRequest(request.runId(), request.stepId(), request.invocationId(),
                request.correlationId(), request.checkpointId(), request.checkpointVersion(),
                request.attempt(), request.snapshot(), request.query(), request.candidateK(),
                request.topK(), filters, request.aclPrincipals(), request.deadline());
    }

    private static List<KnowledgeCandidate> candidates() {
        return List.of(candidate("a", .1), candidate("b", .2), candidate("c", .3));
    }

    private static KnowledgeCandidate candidate(String text, double distance) {
        return new KnowledgeCandidate(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new ArtifactId(UUID.randomUUID()), "char:0-1", "a".repeat(64), text, distance);
    }

    private static final class Fixture {
        private final AtomicInteger embeddingCalls = new AtomicInteger();
        private final AtomicInteger databaseCalls = new AtomicInteger();
        private final AtomicInteger rerankCalls = new AtomicInteger();
        private final List<KnowledgeSearchService.KnowledgeReference> persisted = new ArrayList<>();
        private ProviderFailure embeddingFailure;
        private ProviderFailure rerankFailure;
        private CandidateQueryException databaseFailure;
        private boolean invalidRerank;
        private final KnowledgeSearchService service;

        private Fixture(boolean nonEmpty, List<KnowledgeCandidate> recalled) {
            service = new KnowledgeSearchService(
                    (snapshot, acl) -> nonEmpty,
                    request -> {
                        embeddingCalls.incrementAndGet();
                        return embeddingFailure == null
                                ? new ProviderResult<>(List.of(new float[]{1, 0, 0}),
                                new ProviderUsage(1, 0, null), null)
                                : new ProviderResult<>(null, null, embeddingFailure);
                    },
                    query -> {
                        databaseCalls.incrementAndGet();
                        if (databaseFailure != null) throw databaseFailure;
                        return recalled;
                    },
                    request -> {
                        rerankCalls.incrementAndGet();
                        if (rerankFailure != null) return new ProviderResult<>(null, null, rerankFailure);
                        if (invalidRerank) {
                            return new ProviderResult<>(List.of(), new ProviderUsage(1, 0, null), null);
                        }
                        List<RankedDocument> ranked = new ArrayList<>();
                        for (int index = request.candidates().size() - 1, rank = 1;
                             index >= 0; index--, rank++) {
                            ranked.add(new RankedDocument(request.candidates().get(index).documentId(),
                                    index, 10.0 - rank, rank, RERANK));
                        }
                        return new ProviderResult<>(ranked, new ProviderUsage(1, 0, null), null);
                    },
                    (run, reference) -> persisted.add(reference), audit -> { });
        }

        private List<Integer> calls() {
            return List.of(embeddingCalls.get(), databaseCalls.get(), rerankCalls.get());
        }
    }
}
