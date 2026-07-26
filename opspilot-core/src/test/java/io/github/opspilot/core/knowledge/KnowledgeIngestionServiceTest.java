package io.github.opspilot.core.knowledge;

import io.github.opspilot.core.application.knowledge.KnowledgeIngestionService;
import io.github.opspilot.core.application.knowledge.KnowledgeIngestionService.IngestionRequest;
import io.github.opspilot.core.application.knowledge.KnowledgeIngestionService.KnowledgeIngestionException;
import io.github.opspilot.core.application.knowledge.KnowledgeRetentionService;
import io.github.opspilot.core.application.knowledge.KnowledgeRetentionService.CleanupCandidate;
import io.github.opspilot.core.domain.identity.DomainIds.ArtifactId;
import io.github.opspilot.core.domain.identity.DomainIds.RunId;
import io.github.opspilot.core.port.artifact.ArtifactPort;
import io.github.opspilot.core.port.knowledge.KnowledgeIngestionPort;
import io.github.opspilot.core.port.knowledge.KnowledgeIngestionPort.AcceptedIngestion;
import io.github.opspilot.core.port.knowledge.KnowledgeIngestionPort.IngestionRevision;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeIngestionServiceTest {
    @Test
    void storesRawArtifactBeforeVersionAndCreatesTraceableNormalizedChunks() {
        Fixture fixture = new Fixture(true);

        AcceptedIngestion accepted = fixture.service.ingest(request("key-1", "doc", "Ａ\r\nB"));

        assertEquals(accepted, fixture.repository.accepted.get("key-1"));
        assertEquals("Ａ\r\nB", new String(fixture.stored.getFirst(), StandardCharsets.UTF_8));
        IngestionRevision revision = fixture.repository.revisions.getFirst();
        assertEquals(KnowledgeIngestionService.NORMALIZATION_VERSION, revision.normalizationVersion());
        assertEquals(KnowledgeIngestionService.CHUNK_STRATEGY_VERSION, revision.chunkStrategyVersion());
        assertEquals("A\nB", revision.chunks().getFirst().normalizedText());
        assertEquals("char:0-3", revision.chunks().getFirst().location());
        assertEquals(64, revision.contentSha256().length());
        assertEquals(List.of("role:knowledge"), revision.aclPrincipals());
    }

    @Test
    void rejectsFormatAclMetadataAndAuthorizationBeforeStoringArtifact() {
        Fixture fixture = new Fixture(false);

        assertCode("KNOWLEDGE_FORMAT_NOT_ALLOWED", () -> fixture.service.ingest(
                withMediaType(request("a", "doc", "x"), "application/x-msdownload")));
        assertCode("KNOWLEDGE_ACL_INVALID", () -> fixture.service.ingest(
                withAcl(request("b", "doc", "x"), Set.of("bad principal"))));
        assertCode("KNOWLEDGE_METADATA_INVALID", () -> fixture.service.ingest(
                withMetadata(request("c", "doc", "x"), Map.of("sql", "no"))));
        assertCode("KNOWLEDGE_WRITE_FORBIDDEN", () -> fixture.service.ingest(
                request("d", "doc", "x")));
        assertTrue(fixture.stored.isEmpty());
        assertTrue(fixture.repository.revisions.isEmpty());
    }

    @Test
    void sameIdempotencyKeyAndHashReturnsOriginalButDifferentHashConflicts() {
        Fixture fixture = new Fixture(true);
        IngestionRequest original = request("same", "doc", "one");
        AcceptedIngestion first = fixture.service.ingest(original);
        int artifactCount = fixture.stored.size();

        AcceptedIngestion repeated = fixture.service.ingest(original);

        assertEquals(first, repeated);
        assertEquals(artifactCount, fixture.stored.size());
        assertCode("KNOWLEDGE_IDEMPOTENCY_CONFLICT", () -> fixture.service.ingest(
                withContent(original, "two")));
        assertEquals(1, fixture.repository.revisions.size());
    }

    @Test
    void updateUsesNewImmutableVersionAndLeavesPriorRevisionUnchanged() {
        Fixture fixture = new Fixture(true);
        AcceptedIngestion first = fixture.service.ingest(request("v1", "same-doc", "old"));
        AcceptedIngestion second = fixture.service.ingest(request("v2", "same-doc", "new"));

        assertNotEquals(first.documentVersionId(), second.documentVersionId());
        assertEquals("old", fixture.repository.revisions.get(0).chunks().getFirst().normalizedText());
        assertEquals("new", fixture.repository.revisions.get(1).chunks().getFirst().normalizedText());
    }

    @Test
    void deletionIsImmediateButCleanupPreservesHistoricallyReferencedRevision() {
        UUID document = UUID.randomUUID();
        CleanupCandidate protectedRevision = new CleanupCandidate(document, UUID.randomUUID(), UUID.randomUUID());
        CleanupCandidate disposableRevision = new CleanupCandidate(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        List<UUID> marked = new ArrayList<>();
        List<CleanupCandidate> deleted = new ArrayList<>();
        var port = new KnowledgeRetentionService.KnowledgeLifecyclePort() {
            @Override public void markDeleted(UUID documentId, Instant deletedAt) { marked.add(documentId); }
            @Override public List<CleanupCandidate> cleanupCandidates(Instant olderThan) {
                return List.of(protectedRevision, disposableRevision);
            }
            @Override public boolean hasArtifactRunReferenceOrEvidence(CleanupCandidate candidate) {
                return candidate.equals(protectedRevision);
            }
            @Override public void deletePhysical(CleanupCandidate candidate) { deleted.add(candidate); }
        };
        KnowledgeRetentionService retention = new KnowledgeRetentionService(port);

        retention.delete(document, Instant.parse("2026-07-26T00:00:00Z"));
        var result = retention.clean(Instant.parse("2026-08-26T00:00:00Z"));

        assertEquals(List.of(document), marked);
        assertEquals(List.of(disposableRevision), deleted);
        assertEquals(1, result.deletedCount());
        assertEquals(1, result.protectedCount());
    }

    private static void assertCode(String code, Runnable action) {
        assertEquals(code, assertThrows(KnowledgeIngestionException.class, action::run).getMessage());
    }

    private static IngestionRequest request(String key, String externalKey, String content) {
        return new IngestionRequest(new RunId(UUID.randomUUID()), UUID.randomUUID(), externalKey, key,
                "text/plain", content.getBytes(StandardCharsets.UTF_8),
                Map.of("schemaVersion", "1.0.0", "language", "zh-CN"),
                Set.of("role:knowledge"), UUID.randomUUID(), 512);
    }

    private static IngestionRequest withMediaType(IngestionRequest request, String mediaType) {
        return copy(request, mediaType, request.metadata(), request.aclPrincipals());
    }

    private static IngestionRequest withMetadata(IngestionRequest request, Map<String, String> metadata) {
        return copy(request, request.mediaType(), metadata, request.aclPrincipals());
    }

    private static IngestionRequest withAcl(IngestionRequest request, Set<String> acl) {
        return copy(request, request.mediaType(), request.metadata(), acl);
    }

    private static IngestionRequest withContent(IngestionRequest request, String content) {
        return new IngestionRequest(request.runId(), request.collectionId(), request.externalKey(),
                request.idempotencyKey(), request.mediaType(), content.getBytes(StandardCharsets.UTF_8),
                request.metadata(), request.aclPrincipals(), request.modelRevisionId(),
                request.embeddingDimension());
    }

    private static IngestionRequest copy(
            IngestionRequest request, String mediaType, Map<String, String> metadata, Set<String> acl) {
        return new IngestionRequest(request.runId(), request.collectionId(), request.externalKey(),
                request.idempotencyKey(), mediaType, request.content(), metadata, acl,
                request.modelRevisionId(), request.embeddingDimension());
    }

    private static final class Fixture {
        private final List<byte[]> stored = new ArrayList<>();
        private final FakeRepository repository = new FakeRepository();
        private final KnowledgeIngestionService service;

        private Fixture(boolean authorized) {
            ArtifactPort artifacts = (runId, mediaType, content) -> {
                stored.add(content.clone());
                return new ArtifactId(UUID.randomUUID());
            };
            service = new KnowledgeIngestionService(artifacts, repository,
                    (runId, collectionId, acl) -> authorized);
        }
    }

    private static final class FakeRepository implements KnowledgeIngestionPort {
        private final Map<String, AcceptedIngestion> accepted = new LinkedHashMap<>();
        private final List<IngestionRevision> revisions = new ArrayList<>();

        @Override
        public Optional<AcceptedIngestion> findByIdempotencyKey(String idempotencyKey) {
            return Optional.ofNullable(accepted.get(idempotencyKey));
        }

        @Override
        public AcceptedIngestion accept(IngestionRevision revision) {
            revisions.add(revision);
            accepted.put(revision.identity().idempotencyKey(), revision.identity());
            return revision.identity();
        }
    }
}
