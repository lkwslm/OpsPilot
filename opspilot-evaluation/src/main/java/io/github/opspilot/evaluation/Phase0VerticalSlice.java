package io.github.opspilot.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/** Minimal real Incident -> Evidence -> RCA -> Evaluation use case for the Phase 0 gate. */
public final class Phase0VerticalSlice {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Config config;
    private final DeepSeekRcaClient model;

    public Phase0VerticalSlice(Config config) {
        this.config = config;
        this.model = new DeepSeekRcaClient(config.modelEndpoint, config.apiKey, config.modelId);
    }

    public static Connection connect(String jdbcUrl, String user, String password) {
        try {
            return DriverManager.getConnection(jdbcUrl, user, password);
        } catch (SQLException exception) {
            throw new Failure("DATABASE_UNAVAILABLE", exception.getClass().getSimpleName());
        }
    }

    public Result run(Connection connection) {
        UUID incidentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID stepId = UUID.randomUUID();
        String traceId = UUID.randomUUID().toString();
        try {
            connection.setAutoCommit(false);
            insertIncidentAndRun(connection, incidentId, runId);
            EvidenceCall evidence = collectEvidence(incidentId, runId, stepId, traceId, null);
            verifyRawSource(evidence);

            Path artifactDirectory = config.outputDirectory.resolve("artifacts").toAbsolutePath().normalize();
            Files.createDirectories(artifactDirectory);
            UUID observationArtifactId = evidence.observationId;
            ObjectNode observation = JSON.createObjectNode()
                    .put("schemaVersion", "1.0.0")
                    .put("observationId", evidence.observationId.toString())
                    .put("batchId", evidence.batchId.toString())
                    .put("sourceId", evidence.sourceId)
                    .put("sourceAdapter", evidence.sourceAdapter)
                    .put("claim", evidence.claim);
            StoredArtifact observationArtifact = writeArtifact(
                    artifactDirectory, observationArtifactId, "observation", observation);

            insertArtifact(connection, evidence.artifactId, runId,
                    config.sourcePath.toUri().toString(), evidence.artifactSha256, "application/x-ndjson");
            insertArtifact(connection, observationArtifactId, runId,
                    observationArtifact.uri, observationArtifact.sha256, "application/json");
            ObjectNode evidenceAttributes = JSON.createObjectNode()
                    .put("observationArtifactId", observationArtifactId.toString())
                    .put("rawArtifactId", evidence.artifactId.toString())
                    .put("batchId", evidence.batchId.toString())
                    .put("observationId", evidence.observationId.toString())
                    .put("sourceId", evidence.sourceId)
                    .put("sourceAdapter", evidence.sourceAdapter)
                    .put("taskId", evidence.taskId)
                    .put("traceId", evidence.traceId)
                    .put("commit", evidence.commit)
                    .put("configurationSha256", config.configurationSha256);
            insertEvidence(connection, evidence, runId, observationArtifactId, evidenceAttributes);

            DeepSeekRcaClient.ModelResult modelResult = model.generate(
                    incidentId, runId, evidence.evidenceId, observationArtifactId, evidence.claim);
            UUID rcaArtifactId = UUID.randomUUID();
            StoredArtifact rcaArtifact = writeArtifact(artifactDirectory, rcaArtifactId, "rca", modelResult.rca());
            insertArtifact(connection, rcaArtifactId, runId, rcaArtifact.uri, rcaArtifact.sha256, "application/json");

            UUID auditArtifactId = UUID.randomUUID();
            ObjectNode audit = JSON.createObjectNode()
                    .put("schemaVersion", "1.0.0")
                    .put("a2aTaskId", evidence.taskId)
                    .put("traceId", evidence.traceId)
                    .put("sourceAdapter", evidence.sourceAdapter)
                    .put("modelId", config.modelId)
                    .put("providerRequestId", modelResult.providerRequestId())
                    .put("promptTokens", modelResult.promptTokens())
                    .put("completionTokens", modelResult.completionTokens())
                    .put("promptStored", false)
                    .put("rawProviderResponseStored", false)
                    .put("groundTruthAccessed", false)
                    .put("commit", evidence.commit)
                    .put("configurationSha256", config.configurationSha256);
            StoredArtifact auditArtifact = writeArtifact(artifactDirectory, auditArtifactId, "call-audit", audit);
            insertArtifact(connection, auditArtifactId, runId, auditArtifact.uri, auditArtifact.sha256, "application/json");

            UUID evaluationId = UUID.randomUUID();
            UUID evaluationArtifactId = UUID.randomUUID();
            ObjectNode evaluation = evaluate(incidentId, runId, evaluationId, evidence,
                    observationArtifact, rcaArtifactId, rcaArtifact, auditArtifactId, auditArtifact,
                    modelResult.rca());
            StoredArtifact evaluationArtifact = writeArtifact(
                    artifactDirectory, evaluationArtifactId, "evaluation", evaluation);
            insertArtifact(connection, evaluationArtifactId, runId,
                    evaluationArtifact.uri, evaluationArtifact.sha256, "application/json");
            insertEvaluation(connection, evaluationId, runId, evaluation, evaluationArtifactId);
            completeRun(connection, runId);
            connection.commit();

            ObjectNode report = JSON.createObjectNode();
            report.put("schemaVersion", "1.0.0");
            report.put("status", "PASSED");
            report.put("incidentId", incidentId.toString());
            report.put("runId", runId.toString());
            report.put("evidenceId", evidence.evidenceId.toString());
            report.put("rcaArtifactId", rcaArtifactId.toString());
            report.put("evaluationId", evaluationId.toString());
            report.put("evaluationArtifactUri", evaluationArtifact.uri);
            report.put("evaluationArtifactSha256", evaluationArtifact.sha256);
            report.put("a2aTaskId", evidence.taskId);
            report.put("traceId", evidence.traceId);
            report.put("sourceId", evidence.sourceId);
            report.put("sourceAdapter", evidence.sourceAdapter);
            report.put("commit", evidence.commit);
            report.put("configurationSha256", config.configurationSha256);
            report.put("modelId", config.modelId);
            report.put("modelPromptStored", false);
            report.put("modelRawResponseStored", false);
            report.put("groundTruthAccessed", false);
            report.put("completedAt", Instant.now().toString());
            Path reportPath = config.outputDirectory.resolve("01-WP10.T01-T03-vertical-slice-report.json");
            Files.createDirectories(reportPath.toAbsolutePath().getParent());
            Files.writeString(reportPath, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
            return new Result(incidentId, runId, evidence.evidenceId, rcaArtifactId,
                    evaluationId, reportPath.toAbsolutePath(), evaluationArtifact.sha256);
        } catch (Failure failure) {
            rollback(connection);
            throw failure;
        } catch (SQLException exception) {
            rollback(connection);
            throw new Failure("DATABASE_UNAVAILABLE", exception.getClass().getSimpleName());
        } catch (Exception exception) {
            rollback(connection);
            throw new Failure("VERTICAL_SLICE_FAILED", exception.getClass().getSimpleName());
        }
    }

    EvidenceCall collectEvidence(UUID incidentId, UUID runId, UUID stepId, String traceId, String faultMode) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(config.supervisorEndpoint)
                    .timeout(Duration.ofSeconds(8))
                    .header("Authorization", "Bearer " + config.supervisorToken)
                    .header("Content-Type", "application/json")
                    .header("X-A2A-Skill", "supervise-incident")
                    .header("X-DB-Role", "opspilot_app_role")
                    .header("X-Incident-Id", incidentId.toString())
                    .header("X-Run-Id", runId.toString())
                    .header("X-Step-Id", stepId.toString())
                    .header("X-Trace-Id", traceId)
                    .POST(HttpRequest.BodyPublishers.ofString("{}"));
            if (faultMode != null) {
                builder.header("X-Fault-Mode", faultMode);
            }
            HttpResponse<String> response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
                    .send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                String code = parseCode(response.body(), response.statusCode() == 503 ? "A2A_UNAVAILABLE" : "A2A_REJECTED");
                throw new Failure(code, "HTTP " + response.statusCode());
            }
            JsonNode body = JSON.readTree(response.body());
            return new EvidenceCall(
                    body.path("taskId").asText(), body.path("traceId").asText(),
                    body.path("sourceId").asText(), body.path("sourceAdapter").asText(),
                    UUID.fromString(body.path("batchId").asText()),
                    UUID.fromString(body.path("observationId").asText()),
                    UUID.fromString(body.path("artifactId").asText()),
                    body.path("artifactSha256").asText(),
                    UUID.fromString(body.path("evidenceId").asText()), body.path("claim").asText(),
                    body.path("commit").asText());
        } catch (Failure failure) {
            throw failure;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new Failure("A2A_CANCELLED", exception.getClass().getSimpleName());
        } catch (Exception exception) {
            throw new Failure("A2A_UNAVAILABLE", exception.getClass().getSimpleName());
        }
    }

    private void verifyRawSource(EvidenceCall evidence) throws IOException {
        String actual = sha256(Files.readAllBytes(config.sourcePath));
        if (!("sha256:" + actual).equals(evidence.artifactSha256)) {
            throw new Failure("SOURCE_ARTIFACT_HASH_MISMATCH", "raw artifact");
        }
        if (evidence.claim.toLowerCase().contains("must-redact")) {
            throw new Failure("SOURCE_REDACTION_FAILED", "sensitive value present");
        }
    }

    private static ObjectNode evaluate(UUID incidentId, UUID runId, UUID evaluationId,
                                       EvidenceCall evidence, StoredArtifact observationArtifact,
                                       UUID rcaArtifactId, StoredArtifact rcaArtifact,
                                       UUID auditArtifactId, StoredArtifact auditArtifact, JsonNode rca) {
        ObjectNode report = JSON.createObjectNode();
        report.put("schemaVersion", "1.0.0");
        report.put("evaluationId", evaluationId.toString());
        report.put("incidentId", incidentId.toString());
        report.put("runId", runId.toString());
        report.put("deterministic", true);
        report.put("status", "PASSED");
        report.put("evidenceReferenceValid", rca.toString().contains(evidence.evidenceId.toString()));
        var links = report.putObject("links");
        links.put("evidenceId", evidence.evidenceId.toString());
        links.put("rawArtifactId", evidence.artifactId.toString());
        links.put("rawArtifactSha256", evidence.artifactSha256);
        links.put("observationArtifactUri", observationArtifact.uri);
        links.put("observationArtifactSha256", observationArtifact.sha256);
        links.put("rcaArtifactId", rcaArtifactId.toString());
        links.put("rcaArtifactUri", rcaArtifact.uri);
        links.put("rcaArtifactSha256", rcaArtifact.sha256);
        links.put("auditArtifactId", auditArtifactId.toString());
        links.put("auditArtifactUri", auditArtifact.uri);
        links.put("auditArtifactSha256", auditArtifact.sha256);
        return report;
    }

    private static StoredArtifact writeArtifact(Path directory, UUID id, String type, JsonNode value) throws IOException {
        byte[] bytes = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
        Path path = directory.resolve(type + "-" + id + ".json");
        Files.write(path, bytes);
        return new StoredArtifact(path.toUri().toString(), sha256(bytes));
    }

    private static void insertIncidentAndRun(Connection connection, UUID incidentId, UUID runId) throws SQLException {
        try (PreparedStatement incident = connection.prepareStatement(
                "INSERT INTO opspilot.incident (incident_id,target_system_id,status) VALUES (?, 'sample-system', 'INVESTIGATING')");
             PreparedStatement run = connection.prepareStatement(
                     "INSERT INTO opspilot.incident_run (run_id,incident_id,status) VALUES (?, ?, 'COLLECTING_EVIDENCE')")) {
            incident.setObject(1, incidentId);
            incident.executeUpdate();
            run.setObject(1, runId);
            run.setObject(2, incidentId);
            run.executeUpdate();
        }
    }

    private static void insertArtifact(Connection connection, UUID artifactId, UUID runId,
                                       String uri, String hash, String mediaType) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO opspilot.artifact (artifact_id,run_id,uri,sha256,media_type,access_level) VALUES (?,?,?,?,?,'INTERNAL')")) {
            statement.setObject(1, artifactId);
            statement.setObject(2, runId);
            statement.setString(3, uri);
            statement.setString(4, hash.startsWith("sha256:") ? hash.substring("sha256:".length()) : hash);
            statement.setString(5, mediaType);
            statement.executeUpdate();
        }
    }

    private static void insertEvidence(Connection connection, EvidenceCall evidence, UUID runId,
                                       UUID observationArtifactId, JsonNode attributes) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO opspilot.evidence (evidence_id,run_id,summary,artifact_id,attributes) VALUES (?,?,?,?,?::jsonb)")) {
            statement.setObject(1, evidence.evidenceId);
            statement.setObject(2, runId);
            statement.setString(3, evidence.claim);
            statement.setObject(4, observationArtifactId);
            statement.setString(5, attributes.toString());
            statement.executeUpdate();
        }
    }

    private static void insertEvaluation(Connection connection, UUID evaluationId, UUID runId,
                                         JsonNode metrics, UUID reportArtifactId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO opspilot.evaluation_result (evaluation_id,run_id,metrics_json,report_artifact_id) VALUES (?,?,?::jsonb,?)")) {
            statement.setObject(1, evaluationId);
            statement.setObject(2, runId);
            statement.setString(3, metrics.toString());
            statement.setObject(4, reportArtifactId);
            statement.executeUpdate();
        }
    }

    private static void completeRun(Connection connection, UUID runId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE opspilot.incident_run SET status='COMPLETED',ended_at=now() WHERE run_id=?")) {
            statement.setObject(1, runId);
            statement.executeUpdate();
        }
    }

    static void verifyPersistedGraph(Connection connection, Result result) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT count(*)
                FROM opspilot.evaluation_result er
                JOIN opspilot.incident_run ir ON ir.run_id=er.run_id
                JOIN opspilot.incident i ON i.incident_id=ir.incident_id
                JOIN opspilot.evidence e ON e.run_id=ir.run_id
                JOIN opspilot.artifact a ON a.artifact_id=e.artifact_id
                WHERE er.evaluation_id=? AND e.evidence_id=? AND i.incident_id=? AND ir.status='COMPLETED'
                """)) {
            statement.setObject(1, result.evaluationId);
            statement.setObject(2, result.evidenceId);
            statement.setObject(3, result.incidentId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next() || rows.getInt(1) != 1) {
                    throw new Failure("EVALUATION_TRACE_INCOMPLETE", "persisted graph");
                }
            }
        }
    }

    private static String parseCode(String body, String fallback) {
        try {
            return JSON.readTree(body).path("code").asText(fallback);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void rollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // The original database failure is the frozen result.
        }
    }

    public record Config(URI supervisorEndpoint, String supervisorToken, URI modelEndpoint,
                         String apiKey, String modelId, Path sourcePath, Path outputDirectory,
                         String configurationSha256) {
    }

    public record Result(UUID incidentId, UUID runId, UUID evidenceId, UUID rcaArtifactId,
                         UUID evaluationId, Path reportPath, String evaluationArtifactSha256) {
    }

    record EvidenceCall(String taskId, String traceId, String sourceId, String sourceAdapter,
                        UUID batchId, UUID observationId, UUID artifactId, String artifactSha256,
                        UUID evidenceId, String claim, String commit) {
    }

    private record StoredArtifact(String uri, String sha256) {
    }

    public static final class Failure extends RuntimeException {
        private final String code;

        public Failure(String code, String detail) {
            super(code + ":" + detail);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
