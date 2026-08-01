package io.github.opspilot.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.opspilot.core.application.knowledge.KnowledgeRevisionControlService;
import io.github.opspilot.core.application.knowledge.KnowledgeRevisionControlService.ActivateRevision;
import io.github.opspilot.core.application.knowledge.KnowledgeRevisionControlService.Chunk;
import io.github.opspilot.core.application.knowledge.KnowledgeRevisionControlService.PrepareRevision;
import io.github.opspilot.core.application.knowledge.KnowledgeRevisionControlService.RestoreRevision;
import io.github.opspilot.core.port.knowledge.KnowledgeRevisionControlPort;
import io.github.opspilot.core.port.knowledge.KnowledgeRevisionControlPort.ActivationReceipt;
import io.github.opspilot.core.port.knowledge.KnowledgeRevisionControlPort.RevisionStatus;
import io.github.opspilot.core.port.provider.ProviderContracts.ProviderIdentity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Bearer-protected operator HTTP adapter; installed only on a separate internal listener. */
public final class KnowledgeControlPlaneHandler {
    private static final int MAX_REQUEST_BYTES = 1_048_576;
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final KnowledgeRevisionControlService service;
    private final KnowledgeRevisionControlPort revisions;
    private final byte[] bearerToken;
    private final String principalId;
    private final ModelConfiguration model;

    public KnowledgeControlPlaneHandler(
            KnowledgeRevisionControlService service,
            KnowledgeRevisionControlPort revisions,
            String bearerToken,
            String principalId,
            ModelConfiguration model) {
        this.service = Objects.requireNonNull(service, "service");
        this.revisions = Objects.requireNonNull(revisions, "revisions");
        this.bearerToken = requireText(bearerToken, "bearerToken").getBytes(StandardCharsets.UTF_8);
        this.principalId = requireText(principalId, "principalId");
        this.model = Objects.requireNonNull(model, "model");
    }

    public void install(HttpServer server) {
        server.createContext("/internal/knowledge/revisions/prepare",
                exchange -> handle(exchange, PrepareRequest.class, this::prepare));
        server.createContext("/internal/knowledge/revisions/activate",
                exchange -> handle(exchange, ActivateRequest.class, this::activate));
        server.createContext("/internal/knowledge/revisions/restore",
                exchange -> handle(exchange, RestoreRequest.class, this::restore));
        server.createContext("/internal/knowledge/revisions/active",
                exchange -> handle(exchange, ActiveRequest.class, this::active));
    }

    private RevisionStatus prepare(PrepareRequest request) {
        List<Chunk> chunks = Objects.requireNonNull(request.chunks(), "chunks").stream()
                .map(chunk -> new Chunk(
                        chunk.chunkId(), chunk.externalKey(), chunk.text(),
                        Objects.requireNonNull(chunk.metadata(), "metadata"),
                        Objects.requireNonNull(chunk.aclPrincipals(), "aclPrincipals")))
                .toList();
        return service.prepare(new PrepareRevision(
                request.operationId(), principalId, request.collectionId(), request.revisionId(),
                request.revisionKey(), request.manifestSha256(), model.modelRevisionId(),
                model.identity(), model.embeddingDimension(), model.distanceMetric(),
                request.expiresAt(), chunks));
    }

    private ActivationReceipt activate(ActivateRequest request) {
        return service.activate(new ActivateRevision(
                request.operationId(), principalId, request.collectionId(), request.revisionId(),
                request.expectedActiveRevisionId(), request.receiptExpiresAt()));
    }

    private ActivationReceipt restore(RestoreRequest request) {
        return service.restore(new RestoreRevision(
                request.operationId(), principalId, request.receiptId()));
    }

    private ActiveResponse active(ActiveRequest request) {
        return new ActiveResponse(request.collectionId(),
                revisions.findActiveRevision(request.collectionId()).orElse(null));
    }

    private <T> void handle(
            HttpExchange exchange, Class<T> requestType, Operation<T> operation) throws IOException {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                send(exchange, 405, new ErrorResponse("METHOD_NOT_ALLOWED"));
                return;
            }
            if (!authorized(exchange)) {
                send(exchange, 401, new ErrorResponse("KNOWLEDGE_CONTROL_UNAUTHORIZED"));
                return;
            }
            byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
            if (body.length > MAX_REQUEST_BYTES) {
                send(exchange, 413, new ErrorResponse("KNOWLEDGE_CONTROL_REQUEST_TOO_LARGE"));
                return;
            }
            T request = JSON.readValue(body, requestType);
            send(exchange, 200, operation.apply(request));
        } catch (KnowledgeRevisionControlService.ControlException failure) {
            send(exchange, 409, new ErrorResponse(failure.getMessage()));
        } catch (IllegalArgumentException | NullPointerException failure) {
            send(exchange, 400, new ErrorResponse("KNOWLEDGE_CONTROL_REQUEST_INVALID"));
        } catch (RuntimeException failure) {
            System.err.printf("KNOWLEDGE_CONTROL_AUDIT status=FAILED error=%s%n", failure.getMessage());
            send(exchange, 409, new ErrorResponse(failure.getMessage() == null
                    ? "KNOWLEDGE_CONTROL_OPERATION_FAILED" : failure.getMessage()));
        } finally {
            exchange.close();
        }
    }

    private boolean authorized(HttpExchange exchange) {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) return false;
        byte[] supplied = authorization.substring("Bearer ".length()).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(bearerToken, supplied);
    }

    private static void send(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] body = JSON.writeValueAsBytes(value);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }

    @FunctionalInterface
    private interface Operation<T> {
        Object apply(T request);
    }

    public record ModelConfiguration(
            UUID modelRevisionId,
            ProviderIdentity identity,
            int embeddingDimension,
            String distanceMetric) {
        public ModelConfiguration {
            Objects.requireNonNull(modelRevisionId, "modelRevisionId");
            Objects.requireNonNull(identity, "identity");
            requireText(distanceMetric, "distanceMetric");
            if (embeddingDimension < 1) throw new IllegalArgumentException("embeddingDimension");
        }
    }

    private record PrepareRequest(
            String operationId,
            UUID collectionId,
            UUID revisionId,
            String revisionKey,
            String manifestSha256,
            Instant expiresAt,
            List<ChunkRequest> chunks) {
    }

    private record ChunkRequest(
            UUID chunkId,
            String externalKey,
            String text,
            Map<String, String> metadata,
            Set<String> aclPrincipals) {
    }

    private record ActivateRequest(
            String operationId,
            UUID collectionId,
            UUID revisionId,
            UUID expectedActiveRevisionId,
            Instant receiptExpiresAt) {
    }

    private record RestoreRequest(String operationId, UUID receiptId) {
    }

    private record ActiveRequest(UUID collectionId) {
    }

    private record ActiveResponse(UUID collectionId, UUID activeRevisionId) {
    }

    private record ErrorResponse(String errorCode) {
    }
}
