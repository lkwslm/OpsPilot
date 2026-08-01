package io.github.opspilot.server;

import com.sun.net.httpserver.HttpServer;
import io.github.opspilot.core.application.knowledge.KnowledgeRevisionControlService;
import io.github.opspilot.core.port.knowledge.KnowledgeRevisionControlPort;
import io.github.opspilot.core.port.knowledge.KnowledgeRevisionControlPort.ActivationReceipt;
import io.github.opspilot.core.port.knowledge.KnowledgeRevisionControlPort.PreparedRevision;
import io.github.opspilot.core.port.knowledge.KnowledgeRevisionControlPort.RevisionStatus;
import io.github.opspilot.core.port.provider.ProviderContracts.ProviderIdentity;
import io.github.opspilot.core.port.provider.ProviderContracts.ProviderResult;
import io.github.opspilot.core.port.provider.ProviderContracts.ProviderUsage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class KnowledgeControlPlaneHandlerTest {
    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");
    private static final UUID COLLECTION = UUID.fromString("3eb2a9f3-6e9b-5439-a772-e2052d505fb5");
    private static final UUID REVISION = UUID.fromString("01e066b2-ee42-57ea-8311-0efa2395537e");
    private static final UUID MODEL = UUID.fromString("9c44d65e-e7f4-3d87-a9f1-cb17d155c35d");
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void bearerProtectedHttpAdapterPreparesActivatesAndRestoresRevision() throws Exception {
        FakePort port = new FakePort();
        var service = new KnowledgeRevisionControlService(
                port,
                request -> new ProviderResult<>(List.of(), new ProviderUsage(0, 0, 0L), null),
                Clock.fixed(NOW, ZoneOffset.UTC),
                new KnowledgeRevisionControlService.Policy(Set.of(COLLECTION), 900));
        var model = new KnowledgeControlPlaneHandler.ModelConfiguration(
                MODEL, new ProviderIdentity("infinity", "embedding-test", "revision-test"),
                3, "COSINE");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        new KnowledgeControlPlaneHandler(service, port, "control-token", "fault-lab:phase8", model)
                .install(server);
        server.start();

        HttpResponse<String> unauthorized = post("/internal/knowledge/revisions/prepare", "wrong", prepareBody());
        assertEquals(401, unauthorized.statusCode());

        HttpResponse<String> prepared = post(
                "/internal/knowledge/revisions/prepare", "control-token", prepareBody());
        assertEquals(200, prepared.statusCode());
        assertTrue(prepared.body().contains("\"status\":\"READY\""));
        assertTrue(prepared.body().contains("\"modelRevisionKey\":\"revision-test\""));

        HttpResponse<String> active = post("/internal/knowledge/revisions/activate", "control-token", """
                {"operationId":"activate-http-001","collectionId":"%s","revisionId":"%s",
                 "expectedActiveRevisionId":null,"receiptExpiresAt":"2026-08-01T00:05:00Z"}
                """.formatted(COLLECTION, REVISION));
        assertEquals(200, active.statusCode());
        assertTrue(active.body().contains("\"activatedRevisionId\":\"" + REVISION + "\""));

        HttpResponse<String> restored = post("/internal/knowledge/revisions/restore", "control-token", """
                {"operationId":"restore-http-0001","receiptId":"%s"}
                """.formatted(port.receipt.receiptId()));
        assertEquals(200, restored.statusCode());
        assertTrue(restored.body().contains("\"restoredAt\":\"2026-08-01T00:00:00Z\""));
    }

    private HttpResponse<String> post(String path, String token, String body) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String prepareBody() {
        return """
                {"operationId":"prepare-http-0001","collectionId":"%s","revisionId":"%s",
                 "revisionKey":"phase8/kb-empty/1.0.0","manifestSha256":"%s",
                 "expiresAt":"2026-08-01T00:10:00Z","chunks":[]}
                """.formatted(COLLECTION, REVISION, "a".repeat(64));
    }

    private static final class FakePort implements KnowledgeRevisionControlPort {
        private ActivationReceipt receipt;

        @Override
        public Optional<RevisionStatus> findPrepared(UUID collectionId, UUID revisionId, String digest) {
            return Optional.empty();
        }

        @Override
        public Optional<UUID> findActiveRevision(UUID collectionId) {
            return Optional.empty();
        }

        @Override
        public Optional<ActivationReceipt> findReceipt(UUID receiptId) {
            return Optional.of(receipt);
        }

        @Override
        public RevisionStatus prepare(PreparedRevision revision) {
            return new RevisionStatus(revision.collectionId(), revision.revisionId(), revision.revisionKey(),
                    revision.manifestSha256(), "READY", revision.chunks().size(), revision.modelRevisionId(),
                    revision.modelRevisionKey(), revision.expiresAt());
        }

        @Override
        public ActivationReceipt activate(
                String operationId, String principalId, UUID collectionId, UUID targetRevisionId,
                UUID expectedActiveRevisionId, Instant expiresAt, Instant activatedAt) {
            receipt = new ActivationReceipt(UUID.fromString("44444444-4444-4444-8444-444444444444"),
                    collectionId, expectedActiveRevisionId, targetRevisionId, expiresAt, activatedAt, null);
            return receipt;
        }

        @Override
        public ActivationReceipt restore(
                String operationId, String principalId, ActivationReceipt value, Instant restoredAt) {
            receipt = new ActivationReceipt(value.receiptId(), value.collectionId(), value.previousRevisionId(),
                    value.activatedRevisionId(), value.expiresAt(), value.activatedAt(), restoredAt);
            return receipt;
        }
    }
}
