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
            String professionalResponse, String diagnosisResponse, String remediationResponse,
            Set<String> actualTools, ChatResponse providerResponse) throws Exception {
        JsonNode response = json.readTree(professionalResponse);
        JsonNode items = response.path("evidence");
        if (!items.isArray() || items.isEmpty()) {
            throw new IllegalStateException("PHASE7_EVIDENCE_EMPTY");
        }

        RunId runId = new RunId(runUuid);
        UUID taskId = UUID.fromString(response.path("taskId").asText());
        ArtifactId artifactId = new ArtifactId(UUID.fromString(response.path("artifactId").asText()));
        List<EvidenceWrite> evidence = new ArrayList<>();
        List<EvidenceId> evidenceIds = new ArrayList<>();
        Map<String, EvidenceId> evidenceByCode = new LinkedHashMap<>();
        Set<String> evidenceCodes = new LinkedHashSet<>();
        Set<String> requiredEvidenceTools = new LinkedHashSet<>();
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
            if (evidenceByCode.put(code, evidenceId) != null) {
                throw new IllegalStateException("PHASE7_EVIDENCE_CODE_DUPLICATE");
            }
            requiredEvidenceTools.add(toolFor(signalType));
        }
        if (!actualTools.containsAll(requiredEvidenceTools)) {
            throw new IllegalStateException("PHASE7_REQUIRED_TOOL_NOT_EXECUTED");
        }
        if (actualTools.contains("SandboxTestTool")) {
            throw new IllegalStateException("PHASE7_FORBIDDEN_TOOL_EXECUTED");
        }
        Diagnosis diagnosis = parseDiagnosis(diagnosisResponse, remediationResponse, evidenceCodes);
        Map<DiagnosisHypothesis, HypothesisId> hypothesisIds = new LinkedHashMap<>();
        List<HypothesisWrite> hypotheses = new ArrayList<>();
        List<RelationWrite> relations = new ArrayList<>();
        List<VerificationWrite> verifications = new ArrayList<>();
        for (DiagnosisHypothesis hypothesis : diagnosis.hypotheses()) {
            HypothesisId id = new HypothesisId(UUID.randomUUID());
            hypothesisIds.put(hypothesis, id);
            List<EvidenceId> referenced = new ArrayList<>();
            for (String code : hypothesis.supportingEvidenceCodes()) {
                EvidenceId evidenceId = evidenceByCode.get(code);
                referenced.add(evidenceId);
                relations.add(new RelationWrite(id, evidenceId, "SUPPORTS"));
                verifications.add(new VerificationWrite(UUID.randomUUID(), id, evidenceId,
                        "CONFIRMED", hypothesis.verification()));
            }
            for (String code : hypothesis.conflictingEvidenceCodes()) {
                EvidenceId evidenceId = evidenceByCode.get(code);
                referenced.add(evidenceId);
                relations.add(new RelationWrite(id, evidenceId, "CONFLICTS"));
                verifications.add(new VerificationWrite(UUID.randomUUID(), id, evidenceId,
                        "REFUTED", hypothesis.verification()));
            }
            if (referenced.isEmpty()) throw new IllegalStateException("PHASE7_HYPOTHESIS_EVIDENCE_EMPTY");
            hypotheses.add(new HypothesisWrite(id, hypothesis.title(), referenced.stream().distinct().toList()));
        }
        byte[] payload = professionalResponse.getBytes(StandardCharsets.UTF_8);
        ReceptionMutation mutation = new ReceptionMutation(
                artifactId, runId, evidence,
                hypotheses, relations, verifications,
                List.of(RawFactKind.EVIDENCE), UUID.randomUUID());

        prepareRun(incidentId, runUuid, taskId, windowStart, windowEnd);
        var store = new PostgresSupervisorEvidenceStore(dataSource);
        var receiver = new ArtifactReceiver(new CurrentRunArtifactValidation(), store);
        receiver.receive(RemoteArtifact.json(artifactId, runId, taskId, payload, evidenceIds, mutation));
        finishAnalysisFacts(runUuid, taskId, hypothesisIds, artifactId.value(), actualTools,
                ArtifactReceiver.digest(payload));

        long expectedVersion = currentVersion(runUuid);
        var sealed = new AnalysisSealService(store).seal(runId, expectedVersion, Instant.now());
        var reportService = new RcaReportService(
                store,
                input -> report(input, diagnosis, providerResponse.text()),
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
            UUID runId, UUID taskId, Map<DiagnosisHypothesis, HypothesisId> hypotheses, UUID artifactId,
            Set<String> tools, String requestHash) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            for (var entry : hypotheses.entrySet()) {
                try (var hypothesis = connection.prepareStatement("""
                        UPDATE opspilot.hypothesis SET status=?, confidence=?, updated_at=now()
                        WHERE hypothesis_id=? AND run_id=? AND status='PROPOSED'
                        """)) {
                    hypothesis.setString(1, switch (entry.getKey().status()) {
                        case "SUPPORTED" -> "VERIFIED";
                        case "CONFLICTED" -> "CONFLICTED";
                        case "REJECTED" -> "REJECTED";
                        default -> throw new IllegalStateException("PHASE7_HYPOTHESIS_STATUS_INVALID");
                    });
                    hypothesis.setDouble(2, entry.getKey().confidence());
                    hypothesis.setObject(3, entry.getValue().value());
                    hypothesis.setObject(4, runId);
                    if (hypothesis.executeUpdate() != 1) {
                        throw new IllegalStateException("PHASE7_HYPOTHESIS_UPDATE_CONFLICT");
                    }
                }
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
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("""
                     UPDATE opspilot.incident_run
                     SET status='COMPLETED', outcome=?, execution_ended_at=now(),
                         updated_at=now(), run_version=run_version+1
                     WHERE run_id=? AND status='GENERATING_REPORT'
                     """)) {
                statement.setString(1, outcome);
                statement.setObject(2, runId);
                if (statement.executeUpdate() != 1) {
                    throw new IllegalStateException("PHASE7_RUN_COMPLETION_FAILED:" + rootCauseCode);
                }
            }
            try (var task = connection.prepareStatement("""
                    INSERT INTO opspilot.task
                        (task_id,run_id,task_type,status,max_attempts,idempotency_key,payload_json)
                    SELECT gen_random_uuid(),run.run_id,'EVALUATION','PENDING',3,
                           'evaluation:' || run.run_id::text,
                           jsonb_build_object(
                             'schemaVersion','1.0.0',
                             'scenarioId',incident.scenario_id,
                             'datasetRunId',incident.ticket_json->>'datasetRunId',
                             'groundTruthRelativePath',(incident.ticket_json->>'datasetRunId') || '/ground-truth.json')
                    FROM opspilot.incident_run run
                    JOIN opspilot.incident incident ON incident.incident_id=run.incident_id
                    WHERE run.run_id=? AND incident.scenario_id IS NOT NULL
                      AND jsonb_exists(incident.ticket_json, 'datasetRunId')
                    ON CONFLICT (run_id,task_type,idempotency_key) DO NOTHING
                    """)) {
                task.setObject(1, runId);
                if (task.executeUpdate() != 1) {
                    throw new IllegalStateException("PHASE7_EVALUATION_TASK_NOT_QUEUED");
                }
            }
            connection.commit();
        }
    }

    private StructuredRca report(
            RcaReportService.SealedAnalysis input, Diagnosis diagnosis, String modelSummary) {
        Map<String, RcaReportService.EvidenceView> evidenceByCode = new LinkedHashMap<>();
        input.evidence().forEach(value -> evidenceByCode.put(value.evidenceCode(), value));
        List<String> supportingIds = diagnosis.supportingEvidenceCodes().stream()
                .map(evidenceByCode::get).map(value -> value.evidenceId().wire()).toList();
        List<String> conflictingIds = diagnosis.conflictingEvidenceCodes().stream()
                .map(evidenceByCode::get).map(value -> value.evidenceId().wire()).toList();
        Set<String> citedCodes = new LinkedHashSet<>(diagnosis.supportingEvidenceCodes());
        citedCodes.addAll(diagnosis.conflictingEvidenceCodes());
        List<Citation> citations = citedCodes.stream().map(evidenceByCode::get).map(value -> new Citation(
                "root-cause", value.evidenceId().wire(), value.evidenceCode(), value.artifactId().wire())).toList();
        Map<HypothesisId, List<RcaReportService.RelationView>> relations = new LinkedHashMap<>();
        input.relations().forEach(value -> relations.computeIfAbsent(
                value.hypothesisId(), ignored -> new ArrayList<>()).add(value));
        List<HypothesisResult> hypothesisResults = input.hypotheses().stream().map(value -> {
            List<RcaReportService.RelationView> linked = relations.getOrDefault(value.hypothesisId(), List.of());
            String reportStatus = switch (value.status()) {
                case "VERIFIED", "SUPPORTED" -> "SUPPORTED";
                case "REJECTED" -> "REFUTED";
                default -> "UNVERIFIED";
            };
            return new HypothesisResult(value.hypothesisId().wire(), value.statement(), reportStatus,
                    value.confidence(), linked.stream().filter(item -> "SUPPORTS".equals(item.relation()))
                            .map(item -> item.evidenceId().wire()).toList(),
                    linked.stream().filter(item -> "CONFLICTS".equals(item.relation()))
                            .map(item -> item.evidenceId().wire()).toList());
        }).toList();
        Map<String, List<ActionItem>> actions = new LinkedHashMap<>();
        actions.put("immediate", List.of(new ActionItem(diagnosis.actions().get(0), "立即恢复故障组件", true)));
        actions.put("longTerm", List.of(new ActionItem(diagnosis.actions().get(1), "修复根因并固化配置", true)));
        actions.put("monitoring", List.of(new ActionItem(diagnosis.actions().get(2), "增加可观测性告警", false)));
        actions.put("tests", List.of());
        actions.put("humanNextSteps", List.of());
        actions.put("rollback", List.of());
        return new StructuredRca(
                "1.0.0", input.incidentId().wire(), input.runId().wire(), modelSummary.strip(),
                "HIGH", diagnosis.outcome(),
                diagnosis.rootCauseCode() == null ? null : new RootCause(
                        diagnosis.rootCauseCode(), diagnosis.title(), diagnosis.component(),
                        diagnosis.confidence(), supportingIds, conflictingIds),
                new EvidenceAssessment(
                        input.evidence().isEmpty() ? 0 : (double) citedCodes.size() / input.evidence().size(),
                        diagnosis.missingEvidenceCodes(), List.of()),
                hypothesisResults, actions, citations, diagnosis.limitations(), Instant.now());
    }

    private Diagnosis parseDiagnosis(
            String diagnosisResponse, String remediationResponse, Set<String> evidenceCodes)
            throws Exception {
        JsonNode diagnosisEnvelope = json.readTree(diagnosisResponse);
        JsonNode diagnosis = json.readTree(diagnosisEnvelope.path("content").asText());
        String outcome = diagnosis.path("outcome").asText();
        if (!Set.of("CONCLUSIVE", "PARTIAL", "INCONCLUSIVE").contains(outcome)
                || !diagnosis.path("hypotheses").isArray()
                || diagnosis.path("hypotheses").size() < 2 || diagnosis.path("hypotheses").size() > 4) {
            throw new IllegalStateException("PHASE7_DIAGNOSIS_ARTIFACT_INVALID");
        }
        List<DiagnosisHypothesis> hypotheses = new ArrayList<>();
        for (JsonNode value : diagnosis.path("hypotheses")) {
            String hypothesisTitle = value.path("title").asText();
            String status = value.path("status").asText();
            double confidence = value.path("confidence").asDouble(-1);
            List<String> supporting = strings(value.path("supportingEvidenceCodes"));
            List<String> conflicting = strings(value.path("conflictingEvidenceCodes"));
            String verification = value.path("verification").asText();
            Set<String> referenced = new LinkedHashSet<>(supporting);
            referenced.addAll(conflicting);
            if (hypothesisTitle.isBlank() || verification.isBlank()
                    || !Set.of("SUPPORTED", "CONFLICTED", "REJECTED").contains(status)
                    || confidence < 0 || confidence > 1 || referenced.isEmpty()
                    || !evidenceCodes.containsAll(referenced)) {
                throw new IllegalStateException("PHASE7_HYPOTHESIS_ARTIFACT_INVALID");
            }
            hypotheses.add(new DiagnosisHypothesis(
                    hypothesisTitle, status, confidence, supporting, conflicting, verification));
        }
        JsonNode rootCause = diagnosis.path("rootCause");
        String code = rootCause.isNull() || rootCause.isMissingNode() ? null
                : rootCause.path("rootCauseCode").asText();
        String title = code == null ? null : rootCause.path("title").asText();
        String component = code == null ? null : rootCause.path("component").asText();
        double confidence = code == null ? 0 : rootCause.path("confidence").asDouble(-1);
        List<String> supporting = code == null ? List.of()
                : strings(rootCause.path("supportingEvidenceCodes"));
        List<String> conflicting = code == null ? List.of()
                : strings(rootCause.path("conflictingEvidenceCodes"));
        Set<String> citedCodes = new LinkedHashSet<>(supporting);
        citedCodes.addAll(conflicting);
        if (("INCONCLUSIVE".equals(outcome) && code != null)
                || (!"INCONCLUSIVE".equals(outcome) && (code == null || title.isBlank()
                    || component.isBlank() || confidence < 0 || confidence > 1 || supporting.isEmpty()))
                || !evidenceCodes.containsAll(citedCodes)
                || (code != null && hypotheses.stream().noneMatch(value -> value.title().equals(title)))) {
            throw new IllegalStateException("PHASE7_ROOT_CAUSE_ARTIFACT_INVALID");
        }
        if (code != null) validateFrozenScenarioSupport(code, evidenceCodes);
        JsonNode remediationEnvelope = json.readTree(remediationResponse);
        JsonNode remediation = json.readTree(remediationEnvelope.path("content").asText());
        List<String> actions = new ArrayList<>();
        remediation.path("actions").forEach(item -> actions.add(item.asText()));
        if (actions.size() != 3 || actions.stream().anyMatch(String::isBlank)) {
            throw new IllegalStateException("PHASE7_REMEDIATION_ARTIFACT_INVALID");
        }
        List<String> missing = strings(diagnosis.path("missingEvidenceCodes"));
        List<String> limitations = strings(diagnosis.path("limitations"));
        limitations.addAll(strings(remediation.path("limitations")));
        if (limitations.isEmpty()) limitations.add("仅对当前数据集时间窗和已封账证据成立");
        return new Diagnosis(outcome, code, title, component, confidence, supporting, conflicting,
                hypotheses, missing, actions, List.copyOf(limitations));
    }

    private static List<String> strings(JsonNode values) {
        if (!values.isArray()) throw new IllegalStateException("PHASE7_STRING_ARRAY_INVALID");
        List<String> result = new ArrayList<>();
        values.forEach(value -> {
            String text = value.asText();
            if (text.isBlank() || result.contains(text)) {
                throw new IllegalStateException("PHASE7_STRING_ARRAY_INVALID");
            }
            result.add(text);
        });
        return result;
    }

    private static void validateFrozenScenarioSupport(String code, Set<String> evidenceCodes) {
        Set<String> required = switch (code) {
            case "dependency.latency.inventory" -> Set.of(
                    "trace.order.inventory_span_latency_high",
                    "metric.gateway.request_latency_high");
            case "database.pool.exhausted.order" -> Set.of(
                    "metric.order.hikari_active_at_max",
                    "metric.order.hikari_pending_positive",
                    "log.order.connection_timeout");
            case "service.instance.stopped.inventory" -> Set.of(
                    "health.inventory.unreachable",
                    "log.order.inventory_connection_failed");
            default -> throw new IllegalStateException("PHASE7_ROOT_CAUSE_CODE_UNKNOWN");
        };
        if (!evidenceCodes.containsAll(required)) {
            throw new IllegalStateException("PHASE7_DIAGNOSIS_EVIDENCE_INSUFFICIENT");
        }
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

    private record Diagnosis(
            String outcome, String rootCauseCode, String title, String component, double confidence,
            List<String> supportingEvidenceCodes, List<String> conflictingEvidenceCodes,
            List<DiagnosisHypothesis> hypotheses, List<String> missingEvidenceCodes,
            List<String> actions, List<String> limitations) { }

    private record DiagnosisHypothesis(
            String title, String status, double confidence,
            List<String> supportingEvidenceCodes, List<String> conflictingEvidenceCodes,
            String verification) { }

    static boolean validRawArtifactSha256(String value) {
        return value != null && value.matches("sha256:[a-f0-9]{64}");
    }

    private final class CurrentRunArtifactValidation implements ArtifactReceiver.ValidationPort {
        public boolean jsonSchemaValid(RemoteArtifact artifact) {
            try {
                JsonNode root = json.readTree(artifact.payload());
                Set<String> allowed = Set.of("schemaVersion", "incidentId", "runId", "taskId",
                        "windowStart", "windowEnd", "sourceId", "sourceKind", "adapterId", "batchId",
                        "artifactId", "rawArtifactSha256", "evidence", "agentResult");
                var fields = root.fieldNames();
                while (fields.hasNext()) if (!allowed.contains(fields.next())) return false;
                if (!"1.0.0".equals(root.path("schemaVersion").asText())
                        || !root.path("evidence").isArray() || root.path("evidence").isEmpty()
                        || !validRawArtifactSha256(root.path("rawArtifactSha256").asText())) return false;
                UUID.fromString(root.path("incidentId").asText());
                UUID.fromString(root.path("runId").asText());
                UUID.fromString(root.path("taskId").asText());
                UUID.fromString(root.path("batchId").asText());
                UUID.fromString(root.path("artifactId").asText());
                Instant start = Instant.parse(root.path("windowStart").asText());
                Instant end = Instant.parse(root.path("windowEnd").asText());
                if (!start.isBefore(end)) return false;
                JsonNode agentResult = root.path("agentResult");
                Set<String> agentAllowed = Set.of("schemaVersion", "agentId", "runId", "a2aTaskId",
                        "agentScopeSessionId", "checkpointId", "content", "toolCalls", "usage");
                var agentFields = agentResult.fieldNames();
                while (agentFields.hasNext()) if (!agentAllowed.contains(agentFields.next())) return false;
                if (!"1.0.0".equals(agentResult.path("schemaVersion").asText())
                        || !"evidence-collector".equals(agentResult.path("agentId").asText())
                        || !agentResult.path("toolCalls").isArray()
                        || agentResult.path("content").asText().isBlank()) return false;
                Set<String> allowedTools = Set.of("LogQueryTool", "MetricQueryTool", "TraceQueryTool",
                        "HealthQueryTool", "TopologyQueryTool", "ConfigReadTool");
                Set<String> calledTools = new LinkedHashSet<>();
                for (JsonNode tool : agentResult.path("toolCalls")) {
                    if (!allowedTools.contains(tool.asText()) || !calledTools.add(tool.asText())) return false;
                }
                for (JsonNode item : root.path("evidence")) {
                    Set<String> itemAllowed = Set.of(
                            "evidenceId", "evidenceCode", "claim", "signalType", "observedAt", "artifactIds");
                    var itemFields = item.fieldNames();
                    while (itemFields.hasNext()) if (!itemAllowed.contains(itemFields.next())) return false;
                    UUID.fromString(item.path("evidenceId").asText());
                    if (!item.path("evidenceCode").asText().matches("[a-z0-9]+(?:[._-][a-z0-9]+)+")
                            || item.path("claim").asText().isBlank()
                            || !Set.of("LOG", "METRIC", "TRACE", "HEALTH", "CONFIG")
                                    .contains(item.path("signalType").asText())
                            || !item.path("artifactIds").isArray() || item.path("artifactIds").isEmpty()) return false;
                    Instant.parse(item.path("observedAt").asText());
                    for (JsonNode id : item.path("artifactIds")) UUID.fromString(id.asText());
                }
                return true;
            } catch (Exception invalid) {
                return false;
            }
        }

        public boolean sourceOwnedByRun(RemoteArtifact artifact) {
            try {
                JsonNode root = json.readTree(artifact.payload());
                return artifact.runId().wire().equals(root.path("runId").asText())
                        && artifact.taskId().toString().equals(root.path("taskId").asText())
                        && artifact.artifactId().wire().equals(root.path("artifactId").asText());
            } catch (Exception invalid) { return false; }
        }

        public boolean resourceTaskRunOwned(RemoteArtifact artifact) {
            if (!artifact.artifactId().equals(artifact.mutation().artifactId())
                    || !artifact.runId().equals(artifact.mutation().runId())) return false;
            try (Connection connection = dataSource.getConnection();
                 var statement = connection.prepareStatement("""
                         SELECT EXISTS (SELECT 1 FROM opspilot.task
                           WHERE task_id=? AND run_id=? AND task_type='PHASE7_EVIDENCE'
                             AND status='PENDING')
                         """)) {
                statement.setObject(1, artifact.taskId());
                statement.setObject(2, artifact.runId().value());
                try (var result = statement.executeQuery()) { result.next(); return result.getBoolean(1); }
            } catch (Exception invalid) { return false; }
        }

        public boolean referencesAuthorized(RemoteArtifact artifact) {
            Set<EvidenceId> current = Set.copyOf(artifact.currentRunEvidence());
            Set<EvidenceId> mutations = artifact.mutation().evidence().stream()
                    .map(EvidenceWrite::evidenceId).collect(java.util.stream.Collectors.toSet());
            if (!current.equals(mutations)) return false;
            return artifact.mutation().relations().stream().allMatch(value -> current.contains(value.evidenceId()))
                    && artifact.mutation().verifications().stream()
                            .allMatch(value -> current.contains(value.evidenceId()));
        }

        public boolean domainInvariantsValid(RemoteArtifact artifact) {
            List<EvidenceWrite> evidence = artifact.mutation().evidence();
            return !evidence.isEmpty()
                    && evidence.stream().map(EvidenceWrite::evidenceId).distinct().count() == evidence.size()
                    && evidence.stream().map(EvidenceWrite::evidenceCode).distinct().count() == evidence.size()
                    && evidence.stream().allMatch(value -> value.evidenceCode() != null
                            && value.summary() != null && !value.summary().isBlank()
                            && value.observedAt() != null);
        }
    }
}
