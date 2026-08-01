package io.github.opspilot.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opspilot.a2a.contract.A2aJson;
import io.github.opspilot.a2a.contract.A2aProtocol;
import io.github.opspilot.a2a.contract.A2aSendRequest;
import io.github.opspilot.a2a.contract.A2aTask;
import io.github.opspilot.a2a.contract.A2aTaskState;
import io.github.opspilot.adapters.knowledge.pgvector.KnowledgeRevisionRepository;
import io.github.opspilot.core.port.agent.ChatPort.ChatResponse;
import io.github.opspilot.core.port.agent.ChatPort.TokenUsage;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Supervisor-owned serial production orchestration for the five professional roles. */
final class Phase7RunOrchestrator {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final List<String> ORDER = List.of(
            "evidence-collector", "code-analysis", "knowledge", "diagnosis", "remediation");
    private static final Map<String, String> DB_ROLES = Map.of(
            "evidence-collector", "evidence_agent_role",
            "code-analysis", "code_agent_role",
            "knowledge", "knowledge_agent_role",
            "diagnosis", "diagnosis_agent_role",
            "remediation", "remediation_agent_role");
    private static final Map<String, String> RUN_STATES = Map.of(
            "evidence-collector", "COLLECTING_EVIDENCE",
            "code-analysis", "ANALYZING_CODE",
            "knowledge", "RETRIEVING_KNOWLEDGE",
            "diagnosis", "GENERATING_HYPOTHESES",
            "remediation", "GENERATING_REMEDIATION");

    private final DataSource dataSource;
    private final AgentDirectory directory;
    private final Phase7AgentExecutor supervisor;
    private final Phase7KnowledgeBootstrap knowledgeBootstrap;
    private final HttpClient http;

    Phase7RunOrchestrator(
            DataSource dataSource, AgentDirectory directory, Phase7AgentExecutor supervisor,
            Map<String, String> environment) {
        this(dataSource, directory, supervisor, new Phase7KnowledgeBootstrap(dataSource, environment),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    Phase7RunOrchestrator(
            DataSource dataSource, AgentDirectory directory, Phase7AgentExecutor supervisor,
            Phase7KnowledgeBootstrap knowledgeBootstrap, HttpClient http) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.supervisor = Objects.requireNonNull(supervisor, "supervisor");
        this.knowledgeBootstrap = Objects.requireNonNull(knowledgeBootstrap, "knowledgeBootstrap");
        this.http = Objects.requireNonNull(http, "http");
    }

    void execute(RunRequest run) throws Exception {
        try {
            knowledgeBootstrap.ensureReady();
            freezeKnowledgeSnapshot(run.runId());
        } catch (Exception failure) {
            System.err.printf("PRODUCT_RUN_STAGE_FAILURE stage=KNOWLEDGE_BOOTSTRAP type=%s%n",
                    failure.getClass().getSimpleName());
            throw new ProductRunWorker.ProductRunFailure("PRODUCT_RUN_KNOWLEDGE_BOOTSTRAP_FAILED");
        }
        Map<String, String> artifacts = new LinkedHashMap<>();
        UUID requestId = stableId(run.runId() + ":request");
        UUID traceId = stableId(run.runId() + ":trace");
        for (int ordinal = 0; ordinal < ORDER.size(); ordinal++) {
            String agentId = ORDER.get(ordinal);
            if (!Instant.now().isBefore(run.deadline())) {
                throw new ProductRunWorker.ProductRunFailure("PRODUCT_RUN_DEADLINE_EXCEEDED");
            }
            StepPlan plan = planStep(run.runId(), ordinal, agentId);
            UUID stepId = plan.stepId();
            UUID invocationId = stableId(stepId + ":" + plan.attemptNumber() + ":invocation");
            AgentDirectory.Entry entry = directory.entry(agentId);
            String input = JSON.writeValueAsString(Map.of(
                    "schemaVersion", "1.0.0",
                    "incidentId", run.incidentId().toString(),
                    "runId", run.runId().toString(),
                    "windowStart", run.windowStart().toString(),
                    "windowEnd", run.windowEnd().toString(),
                    "priorArtifacts", Map.copyOf(artifacts)));
            A2aSendRequest request = new A2aSendRequest(
                    run.runId() + ":" + agentId + ":" + plan.attemptNumber(), run.runId().toString(), input, false,
                    "svc:opspilot-server", agentId, entry.expectedSkill(),
                    "application/vnd.opspilot.incident-context+json;v=1",
                    "application/vnd.opspilot." + agentId + ".result+json;v=1",
                    A2aProtocol.VERSION, List.of(A2aProtocol.CORRELATION_EXTENSION), List.of(),
                    requestId.toString(), traceId.toString(), run.runId().toString(),
                    stepId.toString(), null, invocationId.toString());
            String requestHash = sha256(A2aJson.write(request));
            UUID attemptId = beginStep(run, plan, ordinal, agentId, requestHash);
            A2aTask task;
            try {
                task = send(entry, request, run.deadline());
                validateArtifact(task, agentId, run.runId());
                completeStep(run.runId(), stepId, attemptId, agentId, task, requestHash);
            } catch (Exception failure) {
                failStep(stepId, attemptId, failure.getClass().getSimpleName());
                throw failure;
            }
            artifacts.put(agentId, task.artifact().payload());
        }

        try {
            String supervisorInput = JSON.writeValueAsString(Map.of(
                    "schemaVersion", "1.0.0",
                    "incidentId", run.incidentId().toString(),
                    "runId", run.runId().toString(),
                    "professionalArtifacts", artifacts));
            String supervisorArtifact = supervisor.execute(
                    run.productTaskId().toString(), run.runId().toString(),
                    run.incidentId().toString(), supervisorInput);
            JsonNode supervisorResult = JSON.readTree(supervisorArtifact);
            JsonNode usage = supervisorResult.path("usage");
            JsonNode supervisorDecision = JSON.readTree(supervisorResult.path("content").asText());
            String summary = supervisorDecision.path("summary").asText();
            if (summary.isBlank()) throw new IllegalStateException("SUPERVISOR_RESULT_INVALID");
            ChatResponse providerResponse = new ChatResponse(
                    UUID.randomUUID().toString(), summary, List.of(),
                    new TokenUsage(usage.path("inputTokens").asInt(), usage.path("outputTokens").asInt(), 0),
                    "stop");
            Set<String> actualTools = new java.util.LinkedHashSet<>();
            for (String artifact : artifacts.values()) {
                for (JsonNode tool : JSON.readTree(artifact).path("agentResult").path("toolCalls")) {
                    actualTools.add(tool.asText());
                }
            }
            new Phase7AcceptanceService(dataSource, JSON).complete(
                    run.incidentId(), run.runId(), run.windowStart(), run.windowEnd(),
                    artifacts.get("evidence-collector"), artifacts.get("knowledge"),
                    artifacts.get("diagnosis"),
                    artifacts.get("remediation"), actualTools, providerResponse);
        } catch (ProductRunWorker.ProductRunFailure failure) {
            throw failure;
        } catch (Exception failure) {
            String code = finalizationErrorCode(failure);
            String sqlState = failure instanceof SQLException sqlFailure
                    ? sqlFailure.getSQLState() : null;
            System.err.printf(
                    "PRODUCT_RUN_STAGE_FAILURE stage=FINALIZATION code=%s type=%s sqlState=%s site=%s%n",
                    code, failure.getClass().getSimpleName(), sqlState, failureSite(failure));
            throw new ProductRunWorker.ProductRunFailure(code);
        }
    }

    static String finalizationErrorCode(Exception failure) {
        if (failure instanceof SQLException) {
            return "PRODUCT_RUN_FINALIZATION_DATABASE_FAILED";
        }
        String message = failure == null ? null : failure.getMessage();
        return message != null && message.matches("[A-Z][A-Z0-9_]{2,63}")
                ? message : "PRODUCT_RUN_FINALIZATION_FAILED";
    }

    static String failureSite(Exception failure) {
        if (failure == null) return "unknown";
        for (StackTraceElement frame : failure.getStackTrace()) {
            if (frame.getClassName().startsWith("io.github.opspilot.")) {
                return frame.getClassName() + "#" + frame.getMethodName() + ":" + frame.getLineNumber();
            }
        }
        return "external";
    }

    private void freezeKnowledgeSnapshot(UUID runId) throws Exception {
        UUID collectionId;
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT collection_id FROM opspilot.knowledge_collection
                     WHERE active_knowledge_revision_id IS NOT NULL
                     ORDER BY collection_key LIMIT 1
                     """)) {
            try (var result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalStateException("KNOWLEDGE_ACTIVE_COLLECTION_MISSING");
                collectionId = result.getObject(1, UUID.class);
            }
        }
        new KnowledgeRevisionRepository(dataSource).resolveRunSnapshot(runId, collectionId);
    }

    private A2aTask send(
            AgentDirectory.Entry entry, A2aSendRequest request, Instant deadline) throws Exception {
        URI endpoint = URI.create(entry.cardUrl().getScheme() + "://" + entry.cardUrl().getAuthority()
                + "/a2a/messages:send");
        String token = Files.readString(Path.of(entry.tokenRef().substring("file:".length())),
                StandardCharsets.UTF_8).strip();
        Duration remaining = Duration.between(Instant.now(), deadline);
        if (remaining.isNegative() || remaining.isZero()) {
            throw new ProductRunWorker.ProductRunFailure("PRODUCT_RUN_DEADLINE_EXCEEDED");
        }
        HttpRequest call = HttpRequest.newBuilder(endpoint)
                .timeout(remaining)
                .header("Content-Type", A2aProtocol.MEDIA_TYPE)
                .header(A2aProtocol.VERSION_HEADER, A2aProtocol.VERSION)
                .header(A2aProtocol.SERVICE_ID_HEADER, "svc:opspilot-server")
                .header("Authorization", "Bearer " + token)
                .header("X-A2A-Skill", entry.expectedSkill())
                .header("X-DB-Role", DB_ROLES.get(entry.id()))
                .POST(HttpRequest.BodyPublishers.ofString(A2aJson.write(request)))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(call, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ProductRunWorker.ProductRunFailure("PRODUCT_RUN_CANCELLED");
        }
        if (response.statusCode() != 200) {
            throw new ProductRunWorker.ProductRunFailure("PRODUCT_RUN_A2A_FAILED");
        }
        A2aTask task = A2aJson.read(response.body(), A2aTask.class);
        while (!task.state().terminal()) {
            if (!Instant.now().isBefore(deadline)) {
                throw new ProductRunWorker.ProductRunFailure("PRODUCT_RUN_DEADLINE_EXCEEDED");
            }
            try { Thread.sleep(250); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new ProductRunWorker.ProductRunFailure("PRODUCT_RUN_CANCELLED");
            }
            task = fetchTask(entry, task.taskId(), token, deadline);
        }
        if (task.state() != A2aTaskState.COMPLETED) {
            throw new ProductRunWorker.ProductRunFailure("PRODUCT_RUN_A2A_TASK_FAILED");
        }
        return task;
    }

    private A2aTask fetchTask(
            AgentDirectory.Entry entry, String taskId, String token, Instant deadline) throws Exception {
        URI endpoint = URI.create(entry.cardUrl().getScheme() + "://" + entry.cardUrl().getAuthority()
                + "/a2a/tasks/" + taskId);
        Duration remaining = Duration.between(Instant.now(), deadline);
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(remaining)
                .header(A2aProtocol.VERSION_HEADER, A2aProtocol.VERSION)
                .header(A2aProtocol.SERVICE_ID_HEADER, "svc:opspilot-server")
                .header("Authorization", "Bearer " + token)
                .header("X-A2A-Skill", entry.expectedSkill())
                .header("X-DB-Role", DB_ROLES.get(entry.id())).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new ProductRunWorker.ProductRunFailure("PRODUCT_RUN_A2A_RECONCILE_FAILED");
        }
        return A2aJson.read(response.body(), A2aTask.class);
    }

    private StepPlan planStep(UUID runId, int ordinal, String agentId) throws Exception {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT step.step_id,step.status,attempt.attempt_id,attempt.attempt_number,
                            attempt.status
                     FROM opspilot.incident_step step
                     LEFT JOIN LATERAL (
                       SELECT * FROM opspilot.step_attempt value
                       WHERE value.step_id=step.step_id ORDER BY attempt_number DESC LIMIT 1
                     ) attempt ON true
                     WHERE step.run_id=? AND step.ordinal=? AND step.step_type=?
                     """)) {
            statement.setObject(1, runId);
            statement.setInt(2, ordinal);
            statement.setString(3, agentId);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    return new StepPlan(stableId(runId + ":step:" + ordinal), UUID.randomUUID(), 1, true);
                }
                UUID stepId = result.getObject(1, UUID.class);
                UUID attemptId = result.getObject(3, UUID.class);
                int attemptNumber = result.getInt(4);
                String attemptStatus = result.getString(5);
                if (attemptId == null) return new StepPlan(stepId, UUID.randomUUID(), 1, true);
                if (Set.of("FAILED", "CANCELLED", "REJECTED", "SKIPPED").contains(attemptStatus)) {
                    if (attemptNumber >= 3) throw new IllegalStateException("STEP_ATTEMPTS_EXHAUSTED");
                    return new StepPlan(stepId, UUID.randomUUID(), attemptNumber + 1, true);
                }
                return new StepPlan(stepId, attemptId, attemptNumber, false);
            }
        }
    }

    private UUID beginStep(
            RunRequest run, StepPlan plan, int ordinal, String agentId, String requestHash)
            throws Exception {
        UUID stepId = plan.stepId();
        UUID attemptId = plan.attemptId();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var state = connection.prepareStatement(
                    "UPDATE opspilot.incident_run SET status=?, updated_at=now(), run_version=run_version+1 "
                            + "WHERE run_id=? AND analysis_sealed_at IS NULL")) {
                state.setString(1, RUN_STATES.get(agentId));
                state.setObject(2, run.runId());
                if (state.executeUpdate() != 1) throw new IllegalStateException("RUN_STATE_CONFLICT");
            }
            try (var step = connection.prepareStatement("""
                    INSERT INTO opspilot.incident_step (step_id,run_id,step_type,status,ordinal)
                    VALUES (?,?,?,'RUNNING',?)
                    ON CONFLICT (run_id,ordinal) DO UPDATE
                    SET status=CASE WHEN opspilot.incident_step.status='COMPLETED'
                                    THEN 'COMPLETED' ELSE 'RUNNING' END,
                        version=opspilot.incident_step.version+1
                    WHERE opspilot.incident_step.step_id=EXCLUDED.step_id
                      AND opspilot.incident_step.step_type=EXCLUDED.step_type
                    """)) {
                step.setObject(1, stepId);
                step.setObject(2, run.runId());
                step.setString(3, agentId);
                step.setInt(4, ordinal);
                step.executeUpdate();
            }
            if (plan.createAttempt()) try (var attempt = connection.prepareStatement("""
                    INSERT INTO opspilot.step_attempt
                        (attempt_id,step_id,attempt_number,status,idempotency_key,request_hash,
                         remote_agent_id,message_id,session_id,target_skill,remaining_budget_json,
                         parent_deadline,capability_snapshot_json)
                    VALUES (?,?,?,'RUNNING',?,?,?,?,?,?,
                            jsonb_build_object('schemaVersion','1.0.0','deadlineSeconds',?::int),?,
                            jsonb_build_object('schemaVersion','1.0.0','agentId',?::text))
                    """)) {
                attempt.setObject(1, attemptId);
                attempt.setObject(2, stepId);
                attempt.setInt(3, plan.attemptNumber());
                attempt.setString(4, run.runId() + ":" + agentId + ":" + plan.attemptNumber());
                attempt.setString(5, requestHash);
                attempt.setString(6, agentId);
                attempt.setString(7, run.runId() + ":" + agentId + ":" + plan.attemptNumber());
                attempt.setString(8, agentId + ":" + run.runId());
                attempt.setString(9, directory.entry(agentId).expectedSkill());
                attempt.setInt(10, Math.max(1, (int) Duration.between(Instant.now(), run.deadline()).toSeconds()));
                attempt.setTimestamp(11, Timestamp.from(run.deadline()));
                attempt.setString(12, agentId);
                attempt.executeUpdate();
            } else try (var existing = connection.prepareStatement("""
                    SELECT request_hash FROM opspilot.step_attempt
                    WHERE attempt_id=? AND step_id=?
                    """)) {
                existing.setObject(1, attemptId);
                existing.setObject(2, stepId);
                try (var result = existing.executeQuery()) {
                    if (!result.next() || !requestHash.equals(result.getString(1))) {
                        throw new IllegalStateException("STEP_REQUEST_HASH_CONFLICT");
                    }
                }
            }
            connection.commit();
        }
        return attemptId;
    }

    private void completeStep(
            UUID runId, UUID stepId, UUID attemptId, String agentId,
            A2aTask task, String requestHash) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var attempt = connection.prepareStatement("""
                    UPDATE opspilot.step_attempt
                    SET status='COMPLETED', remote_task_id=?, remote_task_state='COMPLETED',
                        updated_at=now(), version=version+1
                    WHERE attempt_id=? AND status='RUNNING'
                    """)) {
                attempt.setString(1, task.taskId());
                attempt.setObject(2, attemptId);
                if (attempt.executeUpdate() != 1) {
                    try (var check = connection.prepareStatement("""
                            SELECT 1 FROM opspilot.step_attempt
                            WHERE attempt_id=? AND status='COMPLETED' AND remote_task_id=?
                            """)) {
                        check.setObject(1, attemptId);
                        check.setString(2, task.taskId());
                        try (var result = check.executeQuery()) {
                            if (!result.next()) throw new IllegalStateException("STEP_ATTEMPT_CONFLICT");
                        }
                    }
                }
            }
            try (var step = connection.prepareStatement(
                    "UPDATE opspilot.incident_step SET status='COMPLETED',version=version+1 "
                            + "WHERE step_id=? AND status='RUNNING'")) {
                step.setObject(1, stepId);
                if (step.executeUpdate() != 1) {
                    try (var check = connection.prepareStatement(
                            "SELECT 1 FROM opspilot.incident_step WHERE step_id=? AND status='COMPLETED'")) {
                        check.setObject(1, stepId);
                        try (var result = check.executeQuery()) {
                            if (!result.next()) throw new IllegalStateException("STEP_STATE_CONFLICT");
                        }
                    }
                }
            }
            try (var audit = connection.prepareStatement("""
                    INSERT INTO opspilot.call_audit
                        (audit_id,run_id,call_kind,action_fingerprint,outcome_code,occurred_at)
                    VALUES (?,?,'A2A',?,'SUCCEEDED',now())
                    ON CONFLICT (audit_id) DO NOTHING
                    """)) {
                audit.setObject(1, stableId(runId + ":a2a-audit:" + requestHash + ":" + agentId));
                audit.setObject(2, runId);
                audit.setString(3, requestHash + ":" + agentId);
                audit.executeUpdate();
            }
            connection.commit();
        }
    }

    private void failStep(UUID stepId, UUID attemptId, String reason) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var attempt = connection.prepareStatement(
                    "UPDATE opspilot.step_attempt SET status='FAILED',updated_at=now() "
                            + "WHERE attempt_id=? AND status='RUNNING'")) {
                attempt.setObject(1, attemptId);
                attempt.executeUpdate();
            }
            try (var step = connection.prepareStatement(
                    "UPDATE opspilot.incident_step SET status='FAILED',version=version+1 WHERE step_id=?")) {
                step.setObject(1, stepId);
                step.executeUpdate();
            }
            connection.commit();
        } catch (Exception ignored) {
            // The product worker records the authoritative Run failure if step persistence is unavailable.
        }
    }

    private static void validateArtifact(A2aTask task, String agentId, UUID runId) {
        if (task == null || task.state() != A2aTaskState.COMPLETED || task.artifact() == null
                || !agentId.equals(task.artifact().ownerAgentId())
                || !runId.toString().equals(task.contextId())
                || !sha256(task.artifact().payload()).equals(task.artifact().sha256())) {
            throw new IllegalStateException("A2A_ARTIFACT_INVALID");
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static UUID stableId(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String bounded(String value) {
        if (value == null) return "UNKNOWN";
        return value.length() <= 64 ? value : value.substring(0, 64);
    }

    record RunRequest(
            UUID incidentId, UUID runId, UUID productTaskId,
            Instant windowStart, Instant windowEnd, Instant deadline) {
        RunRequest {
            Objects.requireNonNull(incidentId, "incidentId");
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(productTaskId, "productTaskId");
            Objects.requireNonNull(windowStart, "windowStart");
            Objects.requireNonNull(windowEnd, "windowEnd");
            Objects.requireNonNull(deadline, "deadline");
            if (!windowStart.isBefore(windowEnd)) throw new IllegalArgumentException("window is invalid");
        }
    }

    private record StepPlan(UUID stepId, UUID attemptId, int attemptNumber, boolean createAttempt) { }
}
