package io.github.opspilot.core.knowledge;

import io.github.opspilot.core.application.knowledge.KnowledgeRevisionControlService;
import io.github.opspilot.core.application.knowledge.KnowledgeRevisionControlService.ActivateRevision;
import io.github.opspilot.core.application.knowledge.KnowledgeRevisionControlService.PrepareRevision;
import io.github.opspilot.core.application.knowledge.KnowledgeRevisionControlService.RestoreRevision;
import io.github.opspilot.core.port.knowledge.KnowledgeRevisionControlPort;
import io.github.opspilot.core.port.knowledge.KnowledgeRevisionControlPort.ActivationReceipt;
import io.github.opspilot.core.port.knowledge.KnowledgeRevisionControlPort.PreparedChunk;
import io.github.opspilot.core.port.knowledge.KnowledgeRevisionControlPort.PreparedRevision;
import io.github.opspilot.core.port.knowledge.KnowledgeRevisionControlPort.RevisionStatus;
import io.github.opspilot.core.port.provider.ProviderContracts.ProviderIdentity;
import io.github.opspilot.core.port.provider.ProviderContracts.ProviderResult;
import io.github.opspilot.core.port.provider.ProviderContracts.ProviderUsage;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KnowledgeRevisionControlServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");
    private static final UUID COLLECTION = UUID.fromString("3eb2a9f3-6e9b-5439-a772-e2052d505fb5");
    private static final UUID REVISION = UUID.fromString("01e066b2-ee42-57ea-8311-0efa2395537e");
    private static final UUID MODEL_REVISION = UUID.fromString("9c44d65e-e7f4-3d87-a9f1-cb17d155c35d");
    private static final String DIGEST = "a".repeat(64);

    @Test
    void prepareEmbedsImmutableChunksAndReturnsReadyRevision() {
        FakePort port = new FakePort();
        var service = service(port);

        RevisionStatus result = service.prepare(new PrepareRevision(
                "fixture-prepare-0001", "fault-lab:phase8", COLLECTION, REVISION,
                "phase8-empty-outcome/1.0.0/no-match", DIGEST, MODEL_REVISION,
                new ProviderIdentity("infinity", "BAAI/bge-small-zh-v1.5", "7999e1d"),
                3, "COSINE", NOW.plusSeconds(600), List.of(
                new KnowledgeRevisionControlService.Chunk(
                        UUID.fromString("11111111-1111-4111-8111-111111111111"),
                        "playbook-a", "排障资料 A", Map.of("language", "zh"), Set.of("reader")),
                new KnowledgeRevisionControlService.Chunk(
                        UUID.fromString("22222222-2222-4222-8222-222222222222"),
                        "playbook-b", "排障资料 B", Map.of("language", "zh"), Set.of("reader")))));

        assertEquals("READY", result.status());
        assertEquals(2, result.chunkCount());
        assertEquals(List.of(1.0f, 0.0f, 0.0f),
                floats(port.prepared.chunks().getFirst().embedding()));
        assertEquals(DIGEST, port.prepared.manifestSha256());
    }

    @Test
    void prepareRejectsCollectionOutsideOperatorAllowlistBeforeEmbedding() {
        var service = service(new FakePort());
        UUID forbidden = UUID.fromString("ffffffff-ffff-4fff-8fff-ffffffffffff");

        var failure = assertThrows(KnowledgeRevisionControlService.ControlException.class,
                () -> service.prepare(new PrepareRevision(
                        "fixture-prepare-0002", "fault-lab:phase8", forbidden, REVISION,
                        "phase8-empty-outcome/1.0.0/kb-empty", DIGEST, MODEL_REVISION,
                        new ProviderIdentity("infinity", "model", "revision"),
                        3, "COSINE", NOW.plusSeconds(600), List.of())));

        assertEquals("KNOWLEDGE_CONTROL_COLLECTION_FORBIDDEN", failure.getMessage());
    }

    @Test
    void activationCarriesExpectedActiveCasAndRestoreRejectsExpiredReceipt() {
        FakePort port = new FakePort();
        var service = service(port);
        UUID original = UUID.fromString("33333333-3333-4333-8333-333333333333");
        ActivationReceipt receipt = service.activate(new ActivateRevision(
                "fixture-activate-0001", "fault-lab:phase8", COLLECTION,
                REVISION, original, NOW.plusSeconds(300)));

        assertEquals(original, receipt.previousRevisionId());
        assertEquals(REVISION, receipt.activatedRevisionId());
        assertEquals(original, port.expectedActive);

        var expiredService = new KnowledgeRevisionControlService(
                port, request -> new ProviderResult<>(List.of(), new ProviderUsage(0, 0, 0L), null),
                Clock.fixed(NOW.plusSeconds(301), ZoneOffset.UTC),
                new KnowledgeRevisionControlService.Policy(Set.of(COLLECTION), 900));
        var failure = assertThrows(KnowledgeRevisionControlService.ControlException.class,
                () -> expiredService.restore(new RestoreRevision(
                        "fixture-restore-0001", "fault-lab:phase8", receipt.receiptId())));

        assertEquals("KNOWLEDGE_CONTROL_RECEIPT_EXPIRED", failure.getMessage());
        assertEquals(0, port.restoreCalls);
    }

    private static KnowledgeRevisionControlService service(FakePort port) {
        return new KnowledgeRevisionControlService(
                port,
                request -> new ProviderResult<>(List.of(
                        new float[]{1, 0, 0}, new float[]{0, 1, 0}),
                        new ProviderUsage(4, 0, 0L), null),
                Clock.fixed(NOW, ZoneOffset.UTC),
                new KnowledgeRevisionControlService.Policy(Set.of(COLLECTION), 900));
    }

    private static List<Float> floats(float[] values) {
        List<Float> result = new java.util.ArrayList<>();
        for (float value : values) result.add(value);
        return result;
    }

    private static final class FakePort implements KnowledgeRevisionControlPort {
        private PreparedRevision prepared;
        private UUID expectedActive;
        private int restoreCalls;

        @Override
        public Optional<RevisionStatus> findPrepared(UUID collectionId, UUID revisionId, String manifestSha256) {
            return Optional.empty();
        }

        @Override
        public Optional<UUID> findActiveRevision(UUID collectionId) {
            return Optional.ofNullable(expectedActive);
        }

        @Override
        public Optional<ActivationReceipt> findReceipt(UUID receiptId) {
            return Optional.of(new ActivationReceipt(
                    receiptId, COLLECTION,
                    UUID.fromString("33333333-3333-4333-8333-333333333333"), REVISION,
                    NOW.plusSeconds(300), NOW, null));
        }

        @Override
        public RevisionStatus prepare(PreparedRevision revision) {
            prepared = revision;
            return new RevisionStatus(revision.collectionId(), revision.revisionId(),
                    revision.revisionKey(), revision.manifestSha256(), "READY",
                    revision.chunks().size(), revision.modelRevisionId(),
                    revision.modelRevisionKey(), revision.expiresAt());
        }

        @Override
        public ActivationReceipt activate(
                String operationId, String principalId, UUID collectionId,
                UUID targetRevisionId, UUID expectedActiveRevisionId,
                Instant receiptExpiresAt, Instant activatedAt) {
            expectedActive = expectedActiveRevisionId;
            return new ActivationReceipt(UUID.fromString("44444444-4444-4444-8444-444444444444"),
                    collectionId, expectedActiveRevisionId, targetRevisionId,
                    receiptExpiresAt, activatedAt, null);
        }

        @Override
        public ActivationReceipt restore(
                String operationId, String principalId, ActivationReceipt receipt, Instant restoredAt) {
            restoreCalls++;
            return new ActivationReceipt(receipt.receiptId(), receipt.collectionId(),
                    receipt.previousRevisionId(), receipt.activatedRevisionId(),
                    receipt.expiresAt(), receipt.activatedAt(), restoredAt);
        }
    }
}
