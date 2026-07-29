package io.github.opspilot.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.opspilot.adapters.persistence.postgres.PostgresProductApiRepository;
import io.github.opspilot.adapters.persistence.postgres.PostgresProductApiRepository.Conflict;
import io.github.opspilot.adapters.persistence.postgres.PostgresProductApiRepository.EventSnapshot;
import io.github.opspilot.adapters.persistence.postgres.PostgresProductApiRepository.IncidentSnapshot;
import io.github.opspilot.adapters.persistence.postgres.PostgresProductApiRepository.NotFound;
import io.github.opspilot.adapters.persistence.postgres.PostgresProductApiRepository.ReportNotReady;
import io.github.opspilot.adapters.persistence.postgres.PostgresProductApiRepository.RunSnapshot;
import io.github.opspilot.adapters.persistence.postgres.PostgresProductApiRepository.StoredResponse;
import io.github.opspilot.core.application.correlation.CorrelationContext;
import io.github.opspilot.server.generated.ProductApiContract.ApprovalDecisionRequest;
import io.github.opspilot.server.generated.ProductApiContract.CreateIncidentRequest;
import io.github.opspilot.server.generated.ProductApiContract.ErrorResponse;
import io.github.opspilot.server.generated.ProductApiContract.IncidentResponse;
import io.github.opspilot.server.generated.ProductApiContract.Outcome;
import io.github.opspilot.server.generated.ProductApiContract.ResumeRunRequest;
import io.github.opspilot.server.generated.ProductApiContract.RunCommandRequest;
import io.github.opspilot.server.generated.ProductApiContract.RunResponse;
import io.github.opspilot.server.generated.ProductApiContract.RunStatus;
import io.github.opspilot.server.generated.ProductApiContract.StartRunRequest;
import io.github.opspilot.server.generated.ProductApiContract.ToolCallPage;
import io.github.opspilot.server.generated.ProductApiContract.ToolCallStatus;
import io.github.opspilot.server.generated.ProductApiContract.ToolCallSummary;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** OpenAPI-derived REST/SSE adapter for the product/Supervisor process. */
public final class ProductApiHandler {
    private static final String PREFIX = "/api/incidents";
    private static final int MAX_REQUEST_BYTES = 1_048_576;
    private static final int MAX_SSE_EVENTS = 100;
    private static final int MAX_SSE_BYTES = 262_144;
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9._:-]{16,128}$");
    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);

    private final PostgresProductApiRepository repository;

    public ProductApiHandler(DataSource dataSource) {
        repository = new PostgresProductApiRepository(dataSource);
    }

    public void install(HttpServer server) {
        server.createContext(PREFIX, this::handle);
    }

    private void handle(HttpExchange exchange) throws IOException {
        UUID requestId = UUID.randomUUID();
        UUID traceId = UUID.randomUUID();
        exchange.getResponseHeaders().set("X-Request-Id", requestId.toString());
        exchange.getResponseHeaders().set("Trace-Id", traceId.toString());
        try {
            String principal = principal(exchange);
            new CorrelationContext(principal, requestId, traceId, null, null, null, null, null);
            Route route = route(exchange);
            switch (route.operation()) {
                case "createIncident" -> createIncident(exchange, principal, requestId, traceId);
                case "getIncident" -> getIncident(exchange, principal, requestId, route.incidentId());
                case "startIncidentRun" -> startRun(exchange, principal, requestId, traceId, route.incidentId());
                case "resumeIncidentRun" -> resumeRun(exchange, principal, requestId, traceId, route.incidentId());
                case "cancelIncidentRun" -> cancelRun(exchange, principal, requestId, traceId, route.incidentId());
                case "getIncidentState" -> getState(exchange, principal, requestId, route.incidentId());
                case "getIncidentReport" -> getReport(exchange, principal, requestId, route.incidentId());
                case "streamIncidentEvents" -> streamEvents(exchange, principal, route.incidentId());
                case "listToolCalls" -> listToolCalls(exchange, principal, requestId, route.incidentId());
                case "decideApproval" -> decideApproval(exchange, principal, requestId, traceId,
                        route.incidentId(), route.approvalId());
                default -> throw new NotFound();
            }
        } catch (BadRequest exception) {
            error(exchange, 400, exception.code, "Invalid request", requestId, false);
        } catch (NotFound exception) {
            error(exchange, 404, "RESOURCE_NOT_FOUND", "Resource not found", requestId, false);
        } catch (ReportNotReady exception) {
            error(exchange, 409, "REPORT_NOT_READY", "Report is not ready", requestId, true);
        } catch (Conflict exception) {
            error(exchange, 409, exception.code(), "Request conflicts with current state", requestId, false);
        } catch (Exception exception) {
            error(exchange, 503, "SERVICE_UNAVAILABLE", "Service temporarily unavailable", requestId, true);
        } finally {
            exchange.close();
        }
    }

    private void createIncident(HttpExchange exchange, String principal, UUID requestId,
            UUID traceId) throws IOException {
        byte[] body = body(exchange);
        CreateIncidentRequest request = decode(body, CreateIncidentRequest.class);
        require(request.targetSystemId() != null && !request.targetSystemId().isBlank()
                && request.targetSystemId().length() <= 256, "INVALID_TARGET_SYSTEM_ID");
        require(request.title() != null && !request.title().isBlank() && request.title().length() <= 200,
                "INVALID_TITLE");
        require(request.severity() != null, "INVALID_SEVERITY");
        require(request.ticket() != null && request.ticket().isObject(), "INVALID_TICKET");
        List<String> resources = request.resourceIds() == null ? List.of() : List.copyOf(request.resourceIds());
        require(resources.size() <= 100 && resources.stream().allMatch(value -> value != null
                && !value.isBlank() && value.length() <= 512)
                && new HashSet<>(resources).size() == resources.size(), "INVALID_RESOURCE_IDS");
        List<UUID> artifacts = request.inputArtifactIds() == null
                ? List.of() : List.copyOf(request.inputArtifactIds());
        require(artifacts.size() <= 100, "INVALID_INPUT_ARTIFACT_IDS");
        UUID incidentId = UUID.randomUUID();
        Instant createdAt = Instant.now();
        StoredResponse response = idempotent(exchange, principal, "createIncident", body,
                requestId, traceId, connection -> {
                    IncidentSnapshot created = repository.createIncident(connection, principal, incidentId,
                            request.targetSystemId(), resources, request.scenarioId(), request.title(),
                            request.severity().name(), json(request.ticket()), artifacts, createdAt);
                    return storedJson(201, Map.of("Content-Type", "application/json",
                                    "Location", "/api/incidents/" + incidentId),
                            incident(requestId, created));
                });
        send(exchange, response);
    }

    private void getIncident(HttpExchange exchange, String principal, UUID requestId,
            UUID incidentId) throws IOException {
        requireMethod(exchange, "GET");
        IncidentSnapshot value = repository.findIncident(principal, incidentId).orElseThrow(NotFound::new);
        sendJson(exchange, 200, incident(requestId, value));
    }

    private void startRun(HttpExchange exchange, String principal, UUID requestId, UUID traceId,
            UUID incidentId) throws IOException {
        byte[] body = body(exchange);
        StartRunRequest request = decode(body, StartRunRequest.class);
        require(request.modelConfigVersion() != null && !request.modelConfigVersion().isBlank()
                && request.modelConfigVersion().length() <= 64, "INVALID_MODEL_CONFIG_VERSION");
        String profile = request.evaluationProfile() == null ? "mvp-v1" : request.evaluationProfile();
        require(!profile.isBlank(), "INVALID_EVALUATION_PROFILE");
        require(request.tokenBudget() == null || request.tokenBudget() > 0, "INVALID_TOKEN_BUDGET");
        int deadline = request.deadlineSeconds() == null ? 600 : request.deadlineSeconds();
        require(deadline >= 60 && deadline <= 3600, "INVALID_DEADLINE");
        UUID runId = UUID.randomUUID();
        StoredResponse response = idempotent(exchange, principal, "startIncidentRun", body,
                requestId, traceId, connection -> storedJson(202, jsonHeader(), run(requestId,
                        repository.startRun(connection, principal, incidentId, runId,
                                request.modelConfigVersion(), profile, request.tokenBudget(), deadline))));
        send(exchange, response);
    }

    private void resumeRun(HttpExchange exchange, String principal, UUID requestId, UUID traceId,
            UUID incidentId) throws IOException {
        byte[] body = body(exchange);
        ResumeRunRequest request = decode(body, ResumeRunRequest.class);
        require(request.runId() != null && request.input() != null && request.input().isObject(),
                "INVALID_RESUME_REQUEST");
        StoredResponse response = idempotent(exchange, principal, "resumeIncidentRun", body,
                requestId, traceId, connection -> storedJson(202, jsonHeader(), run(requestId,
                        repository.resumeRun(connection, principal, incidentId, request.runId()))));
        send(exchange, response);
    }

    private void cancelRun(HttpExchange exchange, String principal, UUID requestId, UUID traceId,
            UUID incidentId) throws IOException {
        byte[] body = body(exchange);
        RunCommandRequest request = decode(body, RunCommandRequest.class);
        require(request.runId() != null && (request.reason() == null || request.reason().length() <= 1000),
                "INVALID_CANCEL_REQUEST");
        StoredResponse response = idempotent(exchange, principal, "cancelIncidentRun", body,
                requestId, traceId, connection -> storedJson(202, jsonHeader(), run(requestId,
                        repository.cancelRun(connection, principal, incidentId, request.runId()))));
        send(exchange, response);
    }

    private void getState(HttpExchange exchange, String principal, UUID requestId,
            UUID incidentId) throws IOException {
        requireMethod(exchange, "GET");
        UUID runId = optionalUuid(query(exchange).get("runId"));
        RunSnapshot value = repository.findRun(principal, incidentId, runId).orElseThrow(NotFound::new);
        sendJson(exchange, 200, run(requestId, value));
    }

    private void getReport(HttpExchange exchange, String principal, UUID requestId,
            UUID incidentId) throws IOException {
        requireMethod(exchange, "GET");
        UUID runId = requiredRunId(exchange);
        var report = repository.report(principal, incidentId, runId);
        String accept = exchange.getRequestHeaders().getFirst("Accept");
        if (accept != null && accept.toLowerCase(Locale.ROOT).contains("text/markdown")) {
            send(exchange, new StoredResponse(200, Map.of("Content-Type", "text/markdown; charset=utf-8"),
                    report.markdown().getBytes(StandardCharsets.UTF_8)));
        } else {
            send(exchange, new StoredResponse(200, jsonHeader(), report.json().getBytes(StandardCharsets.UTF_8)));
        }
    }

    private void listToolCalls(HttpExchange exchange, String principal, UUID requestId,
            UUID incidentId) throws IOException {
        requireMethod(exchange, "GET");
        UUID runId = requiredRunId(exchange);
        Map<String, String> query = query(exchange);
        int offset = query.containsKey("pageToken") ? nonnegativeInt(query.get("pageToken")) : 0;
        var all = repository.toolCalls(principal, incidentId, runId, offset + 101);
        List<ToolCallSummary> page = all.stream().skip(offset).limit(100).map(call -> new ToolCallSummary(
                call.toolCallId(), call.runId(), call.toolName(), ToolCallStatus.valueOf(call.status()),
                call.errorCode(), call.startedAt(), call.endedAt())).toList();
        String next = all.size() > offset + 100 ? Integer.toString(offset + 100) : null;
        sendJson(exchange, 200, new ToolCallPage(page, next));
    }

    private void decideApproval(HttpExchange exchange, String principal, UUID requestId, UUID traceId,
            UUID incidentId, UUID approvalId) throws IOException {
        byte[] body = body(exchange);
        ApprovalDecisionRequest request = decode(body, ApprovalDecisionRequest.class);
        require(request.runId() != null && request.decision() != null
                && (request.reason() == null || request.reason().length() <= 1000),
                "INVALID_APPROVAL_REQUEST");
        StoredResponse response = idempotent(exchange, principal, "decideApproval", body,
                requestId, traceId, connection -> storedJson(202, jsonHeader(), run(requestId,
                        repository.decideApproval(connection, principal, incidentId, request.runId(),
                                approvalId, request.decision().name(), request.reason()))));
        send(exchange, response);
    }

    private void streamEvents(HttpExchange exchange, String principal, UUID incidentId) throws IOException {
        requireMethod(exchange, "GET");
        UUID runId = requiredRunId(exchange);
        long last = lastEventId(exchange);
        List<EventSnapshot> events = repository.eventsAfter(principal, incidentId, runId,
                last, MAX_SSE_EVENTS);
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        if (events.isEmpty()) stream.write(": heartbeat\n\n".getBytes(StandardCharsets.UTF_8));
        for (EventSnapshot event : events) {
            byte[] encoded = ("id: " + event.sequence() + "\n"
                    + "event: " + event.eventType() + "\n"
                    + "data: " + event.payloadJson().replace("\r", "").replace("\n", "") + "\n\n")
                    .getBytes(StandardCharsets.UTF_8);
            if (stream.size() + encoded.length > MAX_SSE_BYTES) break;
            stream.write(encoded);
        }
        send(exchange, new StoredResponse(200, Map.of(
                "Content-Type", "text/event-stream; charset=utf-8",
                "Cache-Control", "no-cache",
                "X-Accel-Buffering", "no"), stream.toByteArray()));
    }

    private StoredResponse idempotent(HttpExchange exchange, String principal, String operation,
            byte[] body, UUID requestId, UUID traceId,
            PostgresProductApiRepository.IdempotentWork work) {
        requireMethod(exchange, "POST");
        String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
        require(key != null && IDEMPOTENCY_KEY.matcher(key).matches(), "INVALID_IDEMPOTENCY_KEY");
        String hash = sha256(exchange.getRequestMethod() + "\n" + exchange.getRequestURI().getPath()
                + "\n" + new String(body, StandardCharsets.UTF_8));
        return repository.executeIdempotent(principal, operation, key, hash, requestId, traceId, work);
    }

    private static Route route(HttpExchange exchange) {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        if (PREFIX.equals(path)) {
            if ("POST".equals(method)) return new Route("createIncident", null, null);
            throw new NotFound();
        }
        if (!path.startsWith(PREFIX + "/")) throw new NotFound();
        String[] parts = path.substring((PREFIX + "/").length()).split("/");
        UUID incidentId = uuid(parts[0]);
        if (parts.length == 1 && "GET".equals(method)) return new Route("getIncident", incidentId, null);
        if (parts.length == 2) {
            return switch (parts[1]) {
                case "run" -> new Route("startIncidentRun", incidentId, null);
                case "resume" -> new Route("resumeIncidentRun", incidentId, null);
                case "cancel" -> new Route("cancelIncidentRun", incidentId, null);
                case "state" -> new Route("getIncidentState", incidentId, null);
                case "report" -> new Route("getIncidentReport", incidentId, null);
                case "events" -> new Route("streamIncidentEvents", incidentId, null);
                case "tool-calls" -> new Route("listToolCalls", incidentId, null);
                default -> throw new NotFound();
            };
        }
        if (parts.length == 3 && "approvals".equals(parts[1])) {
            return new Route("decideApproval", incidentId, uuid(parts[2]));
        }
        throw new NotFound();
    }

    private static String principal(HttpExchange exchange) {
        String principal = exchange.getRequestHeaders().getFirst("X-Principal-Id");
        require(principal != null && !principal.isBlank() && principal.length() <= 256,
                "PRINCIPAL_REQUIRED");
        return principal;
    }

    private static UUID requiredRunId(HttpExchange exchange) {
        UUID runId = optionalUuid(query(exchange).get("runId"));
        require(runId != null, "RUN_ID_REQUIRED");
        return runId;
    }

    private static long lastEventId(HttpExchange exchange) {
        String value = exchange.getRequestHeaders().getFirst("Last-Event-ID");
        if (value == null || value.isBlank()) return 0;
        try {
            long parsed = Long.parseLong(value);
            require(parsed >= 0, "INVALID_LAST_EVENT_ID");
            return parsed;
        } catch (NumberFormatException exception) {
            throw new BadRequest("INVALID_LAST_EVENT_ID");
        }
    }

    private static Map<String, String> query(HttpExchange exchange) {
        Map<String, String> values = new HashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null || raw.isBlank()) return values;
        for (String pair : raw.split("&")) {
            String[] entry = pair.split("=", 2);
            values.put(URLDecoder.decode(entry[0], StandardCharsets.UTF_8),
                    entry.length == 2 ? URLDecoder.decode(entry[1], StandardCharsets.UTF_8) : "");
        }
        return values;
    }

    private static UUID optionalUuid(String value) {
        return value == null || value.isBlank() ? null : uuid(value);
    }

    private static UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException exception) {
            throw new BadRequest("INVALID_UUID");
        }
    }

    private static int nonnegativeInt(String value) {
        try {
            int parsed = Integer.parseInt(value);
            require(parsed >= 0, "INVALID_PAGE_TOKEN");
            return parsed;
        } catch (NumberFormatException exception) {
            throw new BadRequest("INVALID_PAGE_TOKEN");
        }
    }

    private static byte[] body(HttpExchange exchange) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        require(contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("application/json"),
                "UNSUPPORTED_MEDIA_TYPE");
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
        require(bytes.length <= MAX_REQUEST_BYTES, "REQUEST_TOO_LARGE");
        return bytes;
    }

    private static <T> T decode(byte[] body, Class<T> type) {
        try {
            return JSON.readValue(body, type);
        } catch (IOException exception) {
            throw new BadRequest("INVALID_JSON");
        }
    }

    private static IncidentResponse incident(UUID requestId, IncidentSnapshot value) {
        return new IncidentResponse(requestId, value.incidentId(), value.targetSystemId(),
                value.resourceIds(), value.title(),
                io.github.opspilot.server.generated.ProductApiContract.Severity.valueOf(value.severity()),
                value.status(), value.activeRunId(), value.createdAt());
    }

    private static RunResponse run(UUID requestId, RunSnapshot value) {
        return new RunResponse(requestId, value.incidentId(), value.runId(),
                RunStatus.valueOf(value.status()), value.outcome() == null ? null : Outcome.valueOf(value.outcome()),
                value.version(), null);
    }

    private static Map<String, String> jsonHeader() {
        return Map.of("Content-Type", "application/json");
    }

    private static StoredResponse storedJson(int status, Map<String, String> headers, Object value) {
        return new StoredResponse(status, headers, json(value).getBytes(StandardCharsets.UTF_8));
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("JSON_ENCODING_FAILED", exception);
        }
    }

    private static void sendJson(HttpExchange exchange, int status, Object value) throws IOException {
        send(exchange, storedJson(status, jsonHeader(), value));
    }

    private static void send(HttpExchange exchange, StoredResponse response) throws IOException {
        response.headers().forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
        exchange.sendResponseHeaders(response.status(), response.body().length);
        exchange.getResponseBody().write(response.body());
    }

    private static void error(HttpExchange exchange, int status, String code, String title,
            UUID requestId, boolean retryable) throws IOException {
        ErrorResponse error = new ErrorResponse("urn:opspilot:error:" + code.toLowerCase(Locale.ROOT),
                title, status, title, code, requestId, null, null, retryable, null);
        exchange.getResponseHeaders().set("Content-Type", "application/problem+json");
        byte[] body = json(error).getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void requireMethod(HttpExchange exchange, String method) {
        require(method.equals(exchange.getRequestMethod()), "METHOD_NOT_ALLOWED");
    }

    private static void require(boolean condition, String code) {
        if (!condition) throw new BadRequest(code);
    }

    private record Route(String operation, UUID incidentId, UUID approvalId) { }
    private static final class BadRequest extends RuntimeException {
        private final String code;
        private BadRequest(String code) { this.code = code; }
    }
}
