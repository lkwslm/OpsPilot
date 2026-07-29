package io.github.opspilot.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.opspilot.server.generated.ProductApiContract;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ProductApiContractTest {
    private static final String IMAGE = "pgvector/pgvector@sha256:ad2e18408bf447f62092a8a5259e7df10505c5a0360bd1a1853ac8b8b0763da2";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static PostgreSQLContainer postgres;
    private static PGSimpleDataSource dataSource;
    private static HttpServer server;
    private static URI base;

    @BeforeAll
    static void start() throws Exception {
        postgres = new PostgreSQLContainer(IMAGE)
                .withDatabaseName("product_api")
                .withUsername("postgres")
                .withPassword("product-api-test-only");
        postgres.start();
        try (Connection connection = postgresConnection(); Statement statement = connection.createStatement()) {
            statement.execute(resource("/db/bootstrap/roles.sql"));
        }
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").load().migrate();
        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        new ProductApiHandler(dataSource).install(server);
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        if (server != null) server.stop(0);
        if (postgres != null) postgres.stop();
    }

    @Test
    void generatedContractExactlyCoversFrozenOperationIds() throws Exception {
        Path spec = Path.of("..", "docs", "design", "contracts", "openapi", "opspilot-v1.yaml");
        byte[] bytes = Files.readAllBytes(spec);
        Matcher matcher = Pattern.compile("(?m)^\\s+operationId:\\s*([A-Za-z0-9_]+)\\s*$")
                .matcher(new String(bytes, StandardCharsets.UTF_8));
        Set<String> operations = new java.util.LinkedHashSet<>();
        while (matcher.find()) operations.add(matcher.group(1));
        assertEquals(ProductApiContract.OPERATION_IDS, operations);
        assertEquals(ProductApiContract.SPEC_SHA256,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    }

    @Test
    void allOperationsHonorHttpIdempotencyOwnershipAndSseContracts() throws Exception {
        Map<String, Integer> reached = new HashMap<>();
        String createBody = """
                {"targetSystemId":"sample-commerce","resourceIds":["service:orders"],
                 "title":"Checkout failures","severity":"HIGH","ticket":{"id":"INC-1"}}
                """;
        Response created = post("/api/incidents", "create-key-0000001", createBody, "tenant-a");
        reached.put("createIncident", created.status());
        assertEquals(201, created.status());
        UUID incidentId = uuid(created.body(), "incidentId");
        UUID originalRequestId = uuid(created.body(), "requestId");
        Response replay = post("/api/incidents", "create-key-0000001", createBody, "tenant-a");
        assertEquals(created.body(), replay.body());
        assertEquals(originalRequestId, uuid(replay.body(), "requestId"));
        assertEquals(409, post("/api/incidents", "create-key-0000001",
                createBody.replace("Checkout", "Payment"), "tenant-a").status());
        assertEquals(1, count("opspilot.incident", "incident_id", incidentId));

        Response fetched = get("/api/incidents/" + incidentId, "tenant-a", Map.of());
        reached.put("getIncident", fetched.status());
        assertEquals(200, fetched.status());
        assertEquals(404, get("/api/incidents/" + incidentId, "tenant-b", Map.of()).status());

        Response started = post("/api/incidents/" + incidentId + "/run", "start-key-00000001",
                "{\"modelConfigVersion\":\"models-v1\",\"evaluationProfile\":\"mvp-v1\"}",
                "tenant-a");
        reached.put("startIncidentRun", started.status());
        assertEquals(202, started.status());
        UUID runId = uuid(started.body(), "runId");

        Response state = get("/api/incidents/" + incidentId + "/state?runId=" + runId,
                "tenant-a", Map.of());
        reached.put("getIncidentState", state.status());
        assertEquals(200, state.status());

        execute("UPDATE opspilot.incident_run SET status='WAITING_INPUT' WHERE run_id='" + runId + "'");
        Response resumed = post("/api/incidents/" + incidentId + "/resume", "resume-key-0000001",
                "{\"runId\":\"" + runId + "\",\"input\":{\"note\":\"continue\"}}", "tenant-a");
        reached.put("resumeIncidentRun", resumed.status());
        assertEquals(202, resumed.status());
        assertTrue(resumed.body().contains("PLANNING"));

        UUID approvalId = UUID.randomUUID();
        execute("UPDATE opspilot.incident_run SET status='WAITING_APPROVAL' WHERE run_id='" + runId + "'");
        execute("INSERT INTO opspilot.approval (approval_id,run_id,status) VALUES ('"
                + approvalId + "','" + runId + "','PENDING')");
        Response approved = post("/api/incidents/" + incidentId + "/approvals/" + approvalId,
                "approval-key-00001", "{\"runId\":\"" + runId
                        + "\",\"decision\":\"APPROVED\",\"reason\":\"operator reviewed\"}", "tenant-a");
        reached.put("decideApproval", approved.status());
        assertEquals(202, approved.status());

        UUID toolCallId = UUID.randomUUID();
        execute("INSERT INTO opspilot.tool_call "
                + "(tool_call_id,run_id,tool_name,idempotency_key,request_hash,outcome_code) VALUES ('"
                + toolCallId + "','" + runId + "','query_logs','tool-key','" + "1".repeat(64)
                + "','SUCCEEDED')");
        Response tools = get("/api/incidents/" + incidentId + "/tool-calls?runId=" + runId,
                "tenant-a", Map.of());
        reached.put("listToolCalls", tools.status());
        assertEquals(200, tools.status());
        assertTrue(tools.body().contains(toolCallId.toString()));

        Response notReady = get("/api/incidents/" + incidentId + "/report?runId=" + runId,
                "tenant-a", Map.of());
        reached.put("getIncidentReport", notReady.status());
        assertEquals(409, notReady.status());
        assertEquals("REPORT_NOT_READY", JSON.readTree(notReady.body()).get("errorCode").asText());
        UUID artifactId = UUID.randomUUID();
        execute("INSERT INTO opspilot.artifact "
                + "(artifact_id,run_id,uri,sha256,media_type,access_level,object_key,size_bytes) VALUES ('"
                + artifactId + "','" + runId + "','artifact://report','" + "a".repeat(64)
                + "','application/json','INTERNAL','" + artifactId + "',1)");
        execute("INSERT INTO opspilot.rca_report "
                + "(report_id,run_id,report_artifact_id,report_json,report_markdown) VALUES ('"
                + UUID.randomUUID() + "','" + runId + "','" + artifactId
                + "','{\"schemaVersion\":\"1.0.0\",\"rootCauseCode\":\"DB_POOL\"}','# RCA')");
        assertEquals(200, get("/api/incidents/" + incidentId + "/report?runId=" + runId,
                "tenant-a", Map.of("Accept", "application/json")).status());
        Response markdown = get("/api/incidents/" + incidentId + "/report?runId=" + runId,
                "tenant-a", Map.of("Accept", "text/markdown"));
        assertEquals("# RCA", markdown.body());

        execute("INSERT INTO opspilot.sse_event (event_id,run_id,sequence_no,event_type,payload_json) VALUES "
                + "('" + UUID.randomUUID() + "','" + runId
                + "',1001,'RUN_STARTED','{\"schemaVersion\":\"1.0.0\",\"runId\":\"" + runId + "\"}'),"
                + "('" + UUID.randomUUID() + "','" + runId
                + "',1002,'STEP_STARTED','{\"schemaVersion\":\"1.0.0\",\"runId\":\"" + runId + "\"}')");
        Response events = get("/api/incidents/" + incidentId + "/events?runId=" + runId,
                "tenant-a", Map.of());
        reached.put("streamIncidentEvents", events.status());
        assertEquals(200, events.status());
        assertTrue(events.body().contains("id: 1001"));
        Response reconnected = get("/api/incidents/" + incidentId + "/events?runId=" + runId,
                "tenant-a", Map.of("Last-Event-ID", "1001"));
        assertFalse(reconnected.body().contains("id: 1001"));
        assertTrue(reconnected.body().contains("id: 1002"));

        Response cancelled = post("/api/incidents/" + incidentId + "/cancel", "cancel-key-0000001",
                "{\"runId\":\"" + runId + "\",\"reason\":\"operator request\"}", "tenant-a");
        reached.put("cancelIncidentRun", cancelled.status());
        assertEquals(202, cancelled.status());

        String otherBody = createBody.replace("sample-commerce", "other-system").replace("INC-1", "INC-2");
        Response other = post("/api/incidents", "create-key-0000002", otherBody, "tenant-a");
        UUID otherIncident = uuid(other.body(), "incidentId");
        Response otherStarted = post("/api/incidents/" + otherIncident + "/run", "start-key-00000002",
                "{\"modelConfigVersion\":\"models-v1\",\"evaluationProfile\":\"mvp-v1\"}",
                "tenant-a");
        UUID otherRun = uuid(otherStarted.body(), "runId");
        assertEquals(404, post("/api/incidents/" + incidentId + "/cancel", "cross-run-key-0001",
                "{\"runId\":\"" + otherRun + "\"}", "tenant-a").status());
        assertNotEquals("CANCELLING", JSON.readTree(get("/api/incidents/" + otherIncident
                + "/state?runId=" + otherRun, "tenant-a", Map.of()).body()).get("status").asText());
        assertEquals(404, get("/api/incidents/" + otherIncident + "/events?runId=" + otherRun,
                "tenant-a", Map.of("Last-Event-ID", "1001")).status());

        assertEquals(400, get("/api/incidents/not-a-uuid", "tenant-a", Map.of()).status());
        assertEquals(400, post("/api/incidents", "invalid-enum-key1",
                createBody.replace("HIGH", "URGENT"), "tenant-a").status());
        assertEquals(400, post("/api/incidents", null, createBody, "tenant-a").status());
        assertEquals(Map.of(
                "createIncident", 201,
                "getIncident", 200,
                "startIncidentRun", 202,
                "resumeIncidentRun", 202,
                "cancelIncidentRun", 202,
                "getIncidentState", 200,
                "getIncidentReport", 409,
                "streamIncidentEvents", 200,
                "listToolCalls", 200,
                "decideApproval", 202), reached);
    }

    private static Response post(String path, String key, String body, String principal) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve(path))
                .header("Content-Type", "application/json")
                .header("X-Principal-Id", principal);
        if (key != null) request.header("Idempotency-Key", key);
        return send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    private static Response get(String path, String principal, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve(path))
                .header("X-Principal-Id", principal).GET();
        headers.forEach(request::header);
        return send(request.build());
    }

    private static Response send(HttpRequest request) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                request, HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body(),
                response.headers().firstValue("Content-Type").orElse(""));
    }

    private static UUID uuid(String body, String field) throws Exception {
        JsonNode value = JSON.readTree(body).get(field);
        return UUID.fromString(value.asText());
    }

    private static int count(String table, String column, UUID id) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             var result = statement.executeQuery("SELECT count(*) FROM " + table + " WHERE " + column + "='" + id + "'")) {
            result.next();
            return result.getInt(1);
        }
    }

    private static void execute(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static Connection postgresConnection() throws Exception {
        return java.sql.DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static String resource(String path) throws Exception {
        try (var stream = ProductApiContractTest.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("missing resource " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private record Response(int status, String body, String contentType) { }
}
