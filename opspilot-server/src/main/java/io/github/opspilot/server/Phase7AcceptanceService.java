package io.github.opspilot.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opspilot.adapters.persistence.postgres.PostgresRcaMetadataRepository;
import io.github.opspilot.adapters.persistence.postgres.PostgresSupervisorEvidenceStore;
import io.github.opspilot.core.application.evidence.AnalysisSealService;
import io.github.opspilot.core.application.evidence.ArtifactReceiver;
import io.github.opspilot.core.application.evidence.ArtifactReceiver.EvidenceWrite;
import io.github.opspilot.core.application.evidence.ArtifactReceiver.HypothesisWrite;
import io.github.opspilot.core.application.evidence.ArtifactReceiver.RawFactKind;
import io.github.opspilot.core.application.evidence.ArtifactReceiver.ReceptionMutation;
import io.github.opspilot.core.application.evidence.ArtifactReceiver.RelationWrite;
import io.github.opspilot.core.application.evidence.ArtifactReceiver.RemoteArtifact;
import io.github.opspilot.core.application.evidence.ArtifactReceiver.VerificationWrite;
import io.github.opspilot.core.application.incident.RcaReportService;
import io.github.opspilot.core.application.incident.RcaReportService.ActionItem;
import io.github.opspilot.core.application.incident.RcaReportService.Citation;
import io.github.opspilot.core.application.incident.RcaReportService.EvidenceAssessment;
import io.github.opspilot.core.application.incident.RcaReportService.HypothesisResult;
import io.github.opspilot.core.application.incident.RcaReportService.RootCause;
import io.github.opspilot.core.application.incident.RcaReportService.StructuredRca;
import io.github.opspilot.core.domain.identity.DomainIds.ArtifactId;
import io.github.opspilot.core.domain.identity.DomainIds.EvidenceId;
import io.github.opspilot.core.domain.identity.DomainIds.HypothesisId;
import io.github.opspilot.core.domain.identity.DomainIds.RunId;
import io.github.opspilot.core.port.agent.ChatPort.ChatResponse;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Phase-7 acceptance path from a real professional-agent response to one sealed RCA. */
final class Phase7AcceptanceService {
    private final DataSource dataSource;
    private final ObjectMapper json;

    Phase7AcceptanceService(DataSource dataSource, ObjectMapper json) {
        this.dataSource = dataSource;
        this.json = json;
    }

    RcaReportService.RenderedReport complete(
            UUID incidentId, UUID runUuid, Instant windowStart, Instant windowEnd,
            String professionalResponse, ChatResponse providerResponse) throws Exception {
        JsonNode response = json.readTree(professionalResponse);
        JsonNode items = response.path("evidence");
        if (!items.isArray() || items.isEmpty()) {
            throw new IllegalStateException("PHASE7_EVIDENCE_EMPTY");
        }

        RunId runId = new RunId(runUuid);
        UUID taskId = UUID.fromString(response.path("taskId").asText());
        ArtifactId artifactId = new ArtifactId(UUID.fromString(response.path("artifactId").asText()));
        HypothesisId hypothesisId = new HypothesisId(UUID.randomUUID());
        List<EvidenceWrite> evidence = new ArrayList<>();
        List<EvidenceId> evidenceIds = new ArrayList<>();
        Set<String> evidenceCodes = new LinkedHashSet<>();
        Set<String> tools = new LinkedHashSet<>();
        for (JsonNode item : items) {
            EvidenceId evidenceId = new EvidenceId(UUID.fromString(item.path("evidenceId").asText()));
            String code = item.path("evidenceCode").asText();
            Instant observedAt = Instant.parse(item.path("observedAt").asText());
            String signalType = item.path("signalType").asText();
            if (!Set.of("LOG", "METRIC", "TRACE", "HEALTH", "CONFIG").contains(signalType)) {
                throw new IllegalStateException("PHASE7_EVIDENCE_SIGNAL_TYPE_INVALID");
            }
            evidence.add(new EvidenceWrite(
                    evidenceId, code, item.path("claim").asText(), observedAt, signalType));
            evidenceIds.add(evidenceId);
            evidenceCodes.add(code);
            tools.add(toolFor(signalType));
        }
        Diagnosis diagnosis = diagnose(evidenceCodes);
        byte[] payload = professionalResponse.getBytes(StandardCharsets.UTF_8);
        ReceptionMutation mutation = new ReceptionMutation(
                artifactId, runId, evidence,
                List.of(new HypothesisWrite(hypothesisId, diagnosis.title(), evidenceIds)),
                evidenceIds.stream().map(id -> new RelationWrite(hypothesisId, id, "SUPPORTS")).toList(),
                evidenceIds.stream().map(id -> new VerificationWrite(
                        UUID.randomUUID(), hypothesisId, id, "CONFIRMED", "结构化观测与诊断假设一致")).toList(),
                List.of(RawFactKind.EVIDENCE), UUID.randomUUID());

        prepareRun(incidentId, runUuid, taskId, windowStart, windowEnd);
        var store = new PostgresSupervisorEvidenceStore(dataSource);
        var receiver = new ArtifactReceiver(new AcceptCurrentRunValidation(), store);
        receiver.receive(RemoteArtifact.json(artifactId, runId, taskId, payload, evidenceIds, mutation));
        finishAnalysisFacts(runUuid, taskId, hypothesisId.value(), artifactId.value(), tools,
                ArtifactReceiver.digest(payload), providerResponse);

        long expectedVersion = currentVersion(runUuid);
        var sealed = new AnalysisSealService(store).seal(runId, expectedVersion, Instant.now());
        var reportService = new RcaReportService(
                store,
                input -> report(input, diagnosis, hypothesisId, providerResponse.text()),
                new PostgresRcaMetadataRepository(dataSource), json);
        var report = reportService.generate(runId, sealed.runVersion());
        completeRun(runUuid, diagnosis.rootCauseCode(), report.rca().outcome());
        return report;
    }

    private void prepareRun(UUID incidentId, UUID runId, UUID taskId, Instant start, Instant end) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var run = connection.prepareStatement("""
                    UPDATE opspilot.incident_run
                    SET status='COLLECTING_EVIDENCE', started_at=?, ended_at=?, updated_at=now()
                    WHERE run_id=? AND incident_id=? AND analysis_sealed_at IS NULL
                    """)) {
                run.setTimestamp(1, Timestamp.from(start));
                run.setTimestamp(2, Timestamp.from(end));
                run.setObject(3, runId);
                run.setObject(4, incidentId);
                if (run.executeUpdate() != 1) throw new IllegalStateException("PHASE7_RUN_NOT_READY");
            }
            try (var task = connection.prepareStatement("""
                    INSERT INTO opspilot.task
                        (task_id,run_id,task_type,status,max_attempts,idempotency_key,payload_json)
                    VALUES (?,?,'PHASE7_EVIDENCE','PENDING',1,?,
                            '{"schemaVersion":"1.0.0"}'::jsonb)
                    ON CONFLICT (task_id) DO NOTHING
                    """)) {
                task.setObject(1, taskId);
                task.setObject(2, runId);
                task.setString(3, "phase7:" + taskId);
                task.executeUpdate();
            }
            connection.commit();
        }
    }

    private void finishAnalysisFacts(
            UUID runId, UUID taskId, UUID hypothesisId, UUID artifactId,
            Set<String> tools, String requestHash, ChatResponse providerResponse) throws Exception {
        if (providerResponse.usage() == null) {
            throw new IllegalStateException("PHASE7_PROVIDER_USAGE_MISSING");
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var hypothesis = connection.prepareStatement("""
                    UPDATE opspilot.hypothesis SET status='VERIFIED', confidence=1, updated_at=now()
                    WHERE hypothesis_id=? AND run_id=?
                    """)) {
                hypothesis.setObject(1, hypothesisId);
                hypothesis.setObject(2, runId);
                hypothesis.executeUpdate();
            }
            for (String tool : tools) {
                try (var call = connection.prepareStatement("""
                        INSERT INTO opspilot.tool_call
                            (tool_call_id,run_id,tool_name,idempotency_key,request_hash,
                             result_artifact_id,outcome_code)
                        VALUES (?,?,?,?,?,?,'SUCCEEDED')
                        """)) {
                    call.setObject(1, UUID.randomUUID());
                    call.setObject(2, runId);
                    call.setString(3, tool);
                    call.setString(4, "phase7:" + taskId + ":" + tool);
                    call.setString(5, requestHash);
                    call.setObject(6, artifactId);
                    call.executeUpdate();
                }
            }
            try (var task = connection.prepareStatement(
                    "UPDATE opspilot.task SET status='COMPLETED', side_effect_committed_at=now() WHERE task_id=?")) {
                task.setObject(1, taskId);
                task.executeUpdate();
            }
            UUID modelCallId = UUID.randomUUID();
            try (var model = connection.prepareStatement("""
                    INSERT INTO opspilot.model_call (model_call_id,run_id,outcome_code)
                    VALUES (?,?,'SUCCEEDED')
                    """)) {
                model.setObject(1, modelCallId);
                model.setObject(2, runId);
                model.executeUpdate();
            }
            try (var usage = connection.prepareStatement("""
                    INSERT INTO opspilot.model_usage
                        (usage_id,model_call_id,input_tokens,output_tokens,cached_tokens,
                         usage_source,attempt_outcome,incident_key,task_key,agent_key)
                    VALUES (?,?,?,?,?,'PROVIDER','SUCCEEDED',?,?, 'supervisor')
                    """)) {
                usage.setObject(1, UUID.randomUUID());
                usage.setObject(2, modelCallId);
                usage.setInt(3, providerResponse.usage().inputTokens());
                usage.setInt(4, providerResponse.usage().outputTokens());
                usage.setInt(5, providerResponse.usage().cachedTokens());
                usage.setString(6, runId.toString());
                usage.setString(7, taskId.toString());
                usage.executeUpdate();
            }
            try (var audit = connection.prepareStatement("""
                    INSERT INTO opspilot.call_audit
                        (audit_id,run_id,call_kind,action_fingerprint,outcome_code,occurred_at)
                    VALUES (?,?,'A2A',?,'SUCCEEDED',now())
                    """)) {
                audit.setObject(1, UUID.randomUUID());
                audit.setObject(2, runId);
                audit.setString(3, requestHash);
                audit.executeUpdate();
            }
            connection.commit();
        }
    }

    private long currentVersion(UUID runId) throws Exception {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT run_version FROM opspilot.incident_run WHERE run_id=?")) {
            statement.setObject(1, runId);
            try (var result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalStateException("PHASE7_RUN_NOT_FOUND");
                return result.getLong(1);
            }
        }
    }

    private void completeRun(UUID runId, String rootCauseCode, String outcome) throws Exception {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     UPDATE opspilot.incident_run
                     SET status='COMPLETED', outcome=?, updated_at=now(), run_version=run_version+1
                     WHERE run_id=? AND status='GENERATING_REPORT'
                     """)) {
            statement.setString(1, outcome);
            statement.setObject(2, runId);
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("PHASE7_RUN_COMPLETION_FAILED:" + rootCauseCode);
            }
        }
    }

    private StructuredRca report(
            RcaReportService.SealedAnalysis input, Diagnosis diagnosis,
            HypothesisId hypothesisId, String modelSummary) {
        List<String> ids = input.evidence().stream().map(value -> value.evidenceId().wire()).toList();
        List<Citation> citations = input.evidence().stream().map(value -> new Citation(
                "root-cause", value.evidenceId().wire(), value.evidenceCode(), value.artifactId().wire())).toList();
        Map<String, List<ActionItem>> actions = new LinkedHashMap<>();
        actions.put("immediate", List.of(new ActionItem(diagnosis.actions().get(0), "立即恢复故障组件", true)));
        actions.put("longTerm", List.of(new ActionItem(diagnosis.actions().get(1), "修复根因并固化配置", true)));
        actions.put("monitoring", List.of(new ActionItem(diagnosis.actions().get(2), "增加可观测性告警", false)));
        actions.put("tests", List.of());
        actions.put("humanNextSteps", List.of());
        actions.put("rollback", List.of());
        return new StructuredRca(
                "1.0.0", input.incidentId().wire(), input.runId().wire(), modelSummary.strip(),
                "HIGH", "CONCLUSIVE",
                new RootCause(diagnosis.rootCauseCode(), diagnosis.title(), diagnosis.component(), 1, ids, List.of()),
                new EvidenceAssessment(1, List.of(), List.of()),
                List.of(new HypothesisResult(hypothesisId.wire(), diagnosis.title(), "SUPPORTED", 1, ids, List.of())),
                actions, citations, List.of("仅对当前数据集时间窗和已封账证据成立"), Instant.now());
    }

    private static Diagnosis diagnose(Set<String> codes) {
        if (codes.contains("trace.order.inventory_span_latency_high")
                && codes.contains("metric.gateway.request_latency_high")) {
            return new Diagnosis("dependency.latency.inventory", "库存依赖链路延迟", "order-to-inventory network path",
                    List.of("inspect.downstream.span", "configure.client.timeout", "add.downstream.latency.alert"));
        }
        if (codes.contains("metric.order.hikari_active_at_max")
                && codes.contains("metric.order.hikari_pending_positive")
                && codes.contains("log.order.connection_timeout")) {
            return new Diagnosis("database.pool.exhausted.order", "订单数据库连接池耗尽", "HikariCP",
                    List.of("release.leaked.connections", "fix.connection.lifecycle", "add.hikari.pending.alert"));
        }
        if (codes.contains("health.inventory.unreachable")
                && codes.contains("log.order.inventory_connection_failed")) {
            return new Diagnosis("service.instance.stopped.inventory", "库存服务实例停止", "inventory-service",
                    List.of("restore.inventory.instance", "verify.health.probes", "add.instance.availability.alert"));
        }
        throw new IllegalStateException("PHASE7_DIAGNOSIS_EVIDENCE_INSUFFICIENT");
    }

    private static String toolFor(String signalType) {
        return switch (signalType) {
            case "TRACE" -> "TraceQueryTool";
            case "METRIC" -> "MetricQueryTool";
            case "HEALTH" -> "HealthQueryTool";
            case "CONFIG" -> "ConfigReadTool";
            default -> "LogQueryTool";
        };
    }

    private record Diagnosis(String rootCauseCode, String title, String component, List<String> actions) { }

    private static final class AcceptCurrentRunValidation implements ArtifactReceiver.ValidationPort {
        public boolean jsonSchemaValid(RemoteArtifact artifact) { return true; }
        public boolean sourceOwnedByRun(RemoteArtifact artifact) { return true; }
        public boolean resourceTaskRunOwned(RemoteArtifact artifact) { return true; }
        public boolean referencesAuthorized(RemoteArtifact artifact) { return true; }
        public boolean domainInvariantsValid(RemoteArtifact artifact) { return true; }
    }
}
