package io.github.opspilot.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.opspilot.adapters.observability.JsonlLogAdapter;
import io.github.opspilot.core.application.evidence.EvidenceContracts.NormalizationContext;
import io.github.opspilot.core.application.evidence.RuntimeEvidenceNormalizer;
import io.github.opspilot.core.port.observability.ObservationContracts;
import io.github.opspilot.core.port.observability.ObservationContracts.ObservationQuery;
import io.github.opspilot.core.port.observability.ObservationContracts.ResourceRef;
import io.github.opspilot.core.port.observability.ObservationContracts.ResourceType;
import io.github.opspilot.core.port.observability.ObservationContracts.SourceExecutionContext;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;

/** Minimal Phase 0 process used to prove the frozen six-process boundary. */
public final class Phase0Process {
    private static final String PROTOCOL = "1.0";
    private static final String CHAT_MODEL = "deepseek-v4-flash";
    private static final String EMBEDDING_MODEL = "embedding-bge-small-zh-v1.5";
    private static final String RERANK_MODEL = "reranker-bge-v2-m3";
    private static final Map<String, Profile> PROFILES = Map.of(
            "supervisor", new Profile(8080, "supervise-incident", "opspilot_app_role"),
            "evidence-collector", new Profile(8081, "collect-observability-evidence", "evidence_agent_role"),
            "code-analysis", new Profile(8082, "analyze-code-location", "code_agent_role"),
            "knowledge", new Profile(8083, "retrieve-incident-knowledge", "knowledge_agent_role"),
            "diagnosis", new Profile(8084, "generate-and-verify-hypotheses", "diagnosis_agent_role"),
            "remediation", new Profile(8085, "propose-remediation", "remediation_agent_role"));

    private Phase0Process() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            switch (args[0]) {
                case "probe" -> probe(args[1]);
                case "call" -> call(args);
                case "migrate" -> validateMigrations();
                case "retrieval-gate" -> retrievalGate();
                default -> throw new IllegalArgumentException("Unknown command");
            }
            return;
        }
        serve(System.getenv());
    }

    static void serve(Map<String, String> environment) throws Exception {
        RuntimeIdentity identity = RuntimeIdentity.from(environment);
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", identity.profile.port), 0);
        server.createContext("/actuator/health/liveness", exchange -> json(exchange, 200, "{\"status\":\"UP\"}"));
        server.createContext("/actuator/health/readiness", exchange -> {
            Readiness readiness = readiness(identity.readinessUrls);
            json(exchange, readiness.ready ? 200 : 503,
                    "{\"status\":\"" + (readiness.ready ? "UP" : "DOWN")
                            + "\",\"reason\":\"" + readiness.reason + "\"}");
        });
        server.createContext("/.well-known/agent-card.json", exchange -> json(exchange, 200,
                "{\"name\":\"" + identity.agentId + "\",\"protocolVersion\":\"" + PROTOCOL
                        + "\",\"skills\":[{\"id\":\"" + identity.profile.skill + "\"}]}"));
        server.createContext("/a2a/messages:send", exchange -> handleMessage(exchange, identity));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.printf("PROCESS_READY agentId=%s port=%d dbRole=%s%n",
                identity.agentId, identity.profile.port, identity.profile.dbRole);
    }

    static Readiness readiness(String[] urls) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(300)).build();
        for (String url : urls) {
            if (url.isBlank()) {
                continue;
            }
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url.strip()))
                        .timeout(Duration.ofMillis(500)).GET().build();
                HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                System.out.printf("HTTP_AUDIT method=GET target=%s status=%d%n", url, response.statusCode());
                if (response.statusCode() != 200) {
                    return new Readiness(false, "DEPENDENCY_NOT_READY");
                }
            } catch (Exception exception) {
                return new Readiness(false, "DEPENDENCY_UNREACHABLE");
            }
        }
        return new Readiness(true, "READY");
    }

    private static void handleMessage(HttpExchange exchange, RuntimeIdentity identity) throws IOException {
        System.out.printf("HTTP_AUDIT method=%s path=/a2a/messages:send caller=%s%n",
                exchange.getRequestMethod(), exchange.getRemoteAddress().getAddress().getHostAddress());
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        String requestedSkill = exchange.getRequestHeaders().getFirst("X-A2A-Skill");
        String requestedRole = exchange.getRequestHeaders().getFirst("X-DB-Role");
        boolean tokenMatches = authorization != null && authorization.startsWith("Bearer ")
                && MessageDigest.isEqual(identity.serviceToken,
                authorization.substring("Bearer ".length()).getBytes(StandardCharsets.UTF_8));
        if (!"POST".equals(exchange.getRequestMethod()) || !tokenMatches
                || !identity.profile.skill.equals(requestedSkill)
                || !identity.profile.dbRole.equals(requestedRole)) {
            json(exchange, 403, "{\"code\":\"AGENT_IDENTITY_FORBIDDEN\"}");
            return;
        }
        if ("source-unavailable".equals(exchange.getRequestHeaders().getFirst("X-Fault-Mode"))) {
            json(exchange, 503, "{\"code\":\"SOURCE_UNAVAILABLE\"}");
            return;
        }
        if ("supervisor".equals(identity.agentId)) {
            delegateToEvidenceAgent(exchange, identity);
            return;
        }
        if ("evidence-collector".equals(identity.agentId)) {
            collectEvidence(exchange, identity);
            return;
        }
        json(exchange, 202, "{\"taskId\":\"" + UUID.randomUUID() + "\",\"state\":\"SUBMITTED\"}");
    }

    private static void delegateToEvidenceAgent(HttpExchange exchange, RuntimeIdentity identity) throws IOException {
        if (identity.evidenceAgentToken == null) {
            json(exchange, 503, "{\"code\":\"A2A_IDENTITY_UNAVAILABLE\"}");
            return;
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create("http://evidence-agent:8081/a2a/messages:send"))
                .timeout(Duration.ofSeconds(3))
                .header("Authorization", "Bearer " + new String(identity.evidenceAgentToken, StandardCharsets.UTF_8))
                .header("X-A2A-Skill", "collect-observability-evidence")
                .header("X-DB-Role", "evidence_agent_role")
                .POST(HttpRequest.BodyPublishers.ofString("{}"));
        copyHeader(exchange, request, "X-Incident-Id");
        copyHeader(exchange, request, "X-Run-Id");
        copyHeader(exchange, request, "X-Step-Id");
        copyHeader(exchange, request, "X-Trace-Id");
        copyHeader(exchange, request, "X-Fault-Mode");
        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    request.build(), HttpResponse.BodyHandlers.ofString());
            System.out.printf("HTTP_AUDIT method=POST target=http://evidence-agent:8081/a2a/messages:send status=%d%n",
                    response.statusCode());
            json(exchange, response.statusCode(), response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            json(exchange, 503, "{\"code\":\"A2A_CANCELLED\"}");
        } catch (Exception exception) {
            json(exchange, 503, "{\"code\":\"A2A_UNAVAILABLE\"}");
        }
    }

    private static void collectEvidence(HttpExchange exchange, RuntimeIdentity identity) throws IOException {
        try {
            UUID incidentId = headerUuid(exchange, "X-Incident-Id");
            UUID runId = headerUuid(exchange, "X-Run-Id");
            UUID stepId = headerUuid(exchange, "X-Step-Id");
            String traceId = exchange.getRequestHeaders().getFirst("X-Trace-Id");
            if (traceId == null || traceId.isBlank()) {
                traceId = UUID.randomUUID().toString();
            }
            ResourceRef resource = new ResourceRef(
                    "service:sample-system", ResourceType.SERVICE, "sample-system", "sample-system",
                    "phase0", Map.of("composeService", "sample-system"));
            ObservationQuery query = new ObservationQuery(
                    "phase0/jsonl-errors", ObservationContracts.sha256("level=ERROR"),
                    java.time.Instant.parse("2026-07-18T07:55:00Z"),
                    java.time.Instant.parse("2026-07-18T08:05:00Z"), resource);
            var batch = new JsonlLogAdapter(identity.sourcePath).query(
                    query, SourceExecutionContext.authorizedUntil(java.time.Instant.now().plusSeconds(3)));
            var bundle = new RuntimeEvidenceNormalizer().normalizeRuntime(
                    java.util.List.of(batch), new NormalizationContext(incidentId, runId, stepId));
            var evidence = bundle.evidence().getFirst();
            String taskId = UUID.randomUUID().toString();
            json(exchange, 200, "{"
                    + "\"taskId\":\"" + taskId + "\","
                    + "\"state\":\"COMPLETED\","
                    + "\"traceId\":\"" + escape(traceId) + "\","
                    + "\"sourceId\":\"" + batch.source().sourceId() + "\","
                    + "\"sourceAdapter\":\"" + batch.source().adapterId() + ":" + batch.source().adapterVersion() + "\","
                    + "\"batchId\":\"" + batch.batchId() + "\","
                    + "\"observationId\":\"" + batch.observations().getFirst().observationId() + "\","
                    + "\"artifactId\":\"" + batch.rawArtifact().artifactId() + "\","
                    + "\"artifactSha256\":\"" + batch.rawArtifact().sha256() + "\","
                    + "\"evidenceId\":\"" + evidence.evidenceId() + "\","
                    + "\"claim\":\"" + escape(evidence.claim()) + "\","
                    + "\"commit\":\"" + escape(System.getenv().getOrDefault("SOURCE_COMMIT", "working-tree")) + "\","
                    + "\"chatModelId\":\"" + CHAT_MODEL + "\"}");
        } catch (Exception exception) {
            json(exchange, 503, "{\"code\":\"SOURCE_UNAVAILABLE\"}");
        }
    }

    private static UUID headerUuid(HttpExchange exchange, String name) {
        String value = exchange.getRequestHeaders().getFirst(name);
        return value == null ? UUID.randomUUID() : UUID.fromString(value);
    }

    private static void copyHeader(HttpExchange exchange, HttpRequest.Builder request, String name) {
        String value = exchange.getRequestHeaders().getFirst(name);
        if (value != null) {
            request.header(name, value);
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }

    private static void probe(String url) throws Exception {
        int status = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(2)).GET().build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
        if (status != 200) {
            System.exit(1);
        }
    }

    private static void call(String[] args) throws Exception {
        String token = Files.readString(Path.of(args[2]), StandardCharsets.UTF_8).strip();
        HttpRequest request = HttpRequest.newBuilder(URI.create(args[1]))
                .timeout(Duration.ofSeconds(2))
                .header("Authorization", "Bearer " + token)
                .header("X-A2A-Skill", args[3])
                .header("X-DB-Role", args[4])
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                request, HttpResponse.BodyHandlers.ofString());
        int actual = response.statusCode();
        int expected = Integer.parseInt(args[5]);
        System.out.println("HTTP_STATUS=" + actual);
        System.out.println(response.body());
        if (actual != expected) {
            System.exit(1);
        }
    }

    private static void validateMigrations() {
        Path migrations = Path.of("/app/migrations");
        if (!Files.isRegularFile(migrations.resolve("V4__enforce_one_active_run.sql"))) {
            throw new IllegalStateException("MIGRATION_SET_INCOMPATIBLE");
        }
        System.out.println("MIGRATION_SET_VALIDATED version=4");
    }

    private static void retrievalGate() {
        require("CHAT_MODEL_ID", CHAT_MODEL);
        require("EMBEDDING_MODEL_ID", EMBEDDING_MODEL);
        require("RERANK_MODEL_ID", RERANK_MODEL);
        System.out.println("RETRIEVAL_GATE_CONFIGURATION_COMPATIBLE");
    }

    private static void require(String name, String expected) {
        if (!expected.equals(System.getenv(name))) {
            throw new IllegalStateException(name + "_INCOMPATIBLE");
        }
    }

    private static void json(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    record Profile(int port, String skill, String dbRole) {
    }

    record Readiness(boolean ready, String reason) {
    }

    record RuntimeIdentity(
            String agentId,
            Profile profile,
            byte[] serviceToken,
            byte[] evidenceAgentToken,
            String a2aBaseUrl,
            String[] readinessUrls,
            Path sourcePath) {
        static RuntimeIdentity from(Map<String, String> environment) throws IOException {
            String agentId = required(environment, "AGENT_ID");
            Profile profile = PROFILES.get(agentId);
            if (profile == null) {
                throw new IllegalStateException("AGENT_ID_INCOMPATIBLE");
            }
            if (Integer.parseInt(required(environment, "SERVER_PORT")) != profile.port) {
                throw new IllegalStateException("SERVER_PORT_INCOMPATIBLE");
            }
            if (!PROTOCOL.equals(required(environment, "A2A_PROTOCOL"))) {
                throw new IllegalStateException("A2A_PROTOCOL_INCOMPATIBLE");
            }
            if (!CHAT_MODEL.equals(required(environment, "CHAT_MODEL_ID"))) {
                throw new IllegalStateException("CHAT_MODEL_ID_INCOMPATIBLE");
            }
            if (!profile.skill.equals(required(environment, "AGENT_SKILL"))) {
                throw new IllegalStateException("AGENT_SKILL_INCOMPATIBLE");
            }
            if (!profile.dbRole.equals(required(environment, "DB_ROLE"))) {
                throw new IllegalStateException("DB_ROLE_INCOMPATIBLE");
            }
            String tokenFile = required(environment, "SERVICE_TOKEN_FILE");
            byte[] token = Files.readString(Path.of(tokenFile), StandardCharsets.UTF_8).strip()
                    .getBytes(StandardCharsets.UTF_8);
            if (token.length < 16) {
                throw new IllegalStateException("SERVICE_TOKEN_INCOMPATIBLE");
            }
            String a2aBaseUrl = required(environment, "A2A_BASE_URL");
            URI.create(a2aBaseUrl);
            byte[] evidenceAgentToken = null;
            if ("supervisor".equals(agentId)) {
                evidenceAgentToken = Files.readString(
                                Path.of(required(environment, "EVIDENCE_AGENT_TOKEN_FILE")), StandardCharsets.UTF_8)
                        .strip().getBytes(StandardCharsets.UTF_8);
            }
            Path sourcePath = "evidence-collector".equals(agentId)
                    ? Path.of(required(environment, "SOURCE_PATH")) : null;
            String readiness = environment.getOrDefault("READINESS_URLS", "");
            return new RuntimeIdentity(agentId, profile, token, evidenceAgentToken, a2aBaseUrl,
                    readiness.isBlank() ? new String[0] : Arrays.stream(readiness.split(",")).toArray(String[]::new),
                    sourcePath);
        }

        private static String required(Map<String, String> environment, String name) {
            String value = environment.get(name);
            if (value == null || value.isBlank()) {
                throw new IllegalStateException(name + "_MISSING");
            }
            return value;
        }
    }
}
