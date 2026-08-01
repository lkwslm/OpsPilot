package io.github.opspilot.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opspilot.evaluation.EvaluationModels.CitationObservation;
import io.github.opspilot.evaluation.EvaluationModels.Efficiency;
import io.github.opspilot.evaluation.EvaluationModels.EvaluationInput;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Reconstructs evaluation input exclusively from persisted raw run facts. */
final class EvaluationRunReader {
    private final DataSource dataSource;
    private final ObjectMapper json;

    EvaluationRunReader(DataSource dataSource, ObjectMapper json) {
        this.dataSource = dataSource;
        this.json = json;
    }

    EvaluationInput read(UUID runId, String scenarioId) {
        try (Connection connection = dataSource.getConnection()) {
            RunFact run = run(connection, runId);
            JsonNode rca = rca(connection, runId);
            Map<String, EvidenceFact> evidence = evidence(connection, runId);
            List<CitationObservation> citations = new ArrayList<>();
            for (JsonNode citation : rca.path("citations")) {
                EvidenceFact fact = evidence.get(citation.path("evidenceId").asText());
                citations.add(new CitationObservation(
                        citation.path("evidenceCode").asText(), fact != null,
                        fact != null && runId.equals(fact.runId()),
                        fact != null && !Set.of("GROUND_TRUTH", "EVALUATION_ONLY").contains(fact.accessLevel()),
                        fact != null && fact.artifactSha256().matches("[0-9a-f]{64}"),
                        fact != null && !fact.observedAt().isBefore(run.startedAt())
                                && !fact.observedAt().isAfter(run.endedAt()),
                        fact != null && fact.evidenceCode().equals(citation.path("evidenceCode").asText())));
            }
            Set<String> tools = strings(connection,
                    "SELECT DISTINCT tool_name FROM opspilot.tool_call WHERE run_id=?", runId);
            int toolCalls = count(connection, "SELECT count(*) FROM opspilot.tool_call WHERE run_id=?", runId);
            int modelCalls = count(connection, "SELECT count(*) FROM opspilot.model_call WHERE run_id=?", runId);
            int a2aCalls = count(connection,
                    "SELECT count(*) FROM opspilot.call_audit WHERE run_id=? AND call_kind='A2A'", runId);
            Usage usage = usage(connection, runId);
            long wallClock = Math.max(0, Duration.between(
                    run.executionStartedAt(), run.executionEndedAt()).toMillis());
            return new EvaluationInput(
                    runId.toString(), scenarioId, rca.path("outcome").asText(),
                    rca.path("rootCause").path("rootCauseCode").asText(null), citations, tools,
                    run.status(), !rca.isMissingNode() && "1.0.0".equals(rca.path("schemaVersion").asText()),
                    markdownPresent(connection, runId), true, List.of(),
                    new Efficiency(1, modelCalls, a2aCalls, toolCalls,
                            usage.inputTokens(), usage.outputTokens(), wallClock, usage.costMicros()));
        } catch (Exception exception) {
            throw new IllegalStateException("EVALUATION_RAW_FACT_READ_FAILED", exception);
        }
    }

    private static RunFact run(Connection connection, UUID runId) throws Exception {
        try (var statement = connection.prepareStatement("""
                SELECT status,started_at,COALESCE(ended_at,analysis_sealed_at,now()),
                       execution_started_at,COALESCE(execution_ended_at,now())
                FROM opspilot.incident_run WHERE run_id=?
                """)) {
            statement.setObject(1, runId);
            try (var result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalStateException("EVALUATION_RUN_NOT_FOUND");
                return new RunFact(result.getString(1), result.getTimestamp(2).toInstant(),
                        result.getTimestamp(3).toInstant(), result.getTimestamp(4).toInstant(),
                        result.getTimestamp(5).toInstant());
            }
        }
    }

    private JsonNode rca(Connection connection, UUID runId) throws Exception {
        try (var statement = connection.prepareStatement("""
                SELECT report_json::text FROM opspilot.rca_report
                WHERE run_id=? ORDER BY run_version DESC LIMIT 1
                """)) {
            statement.setObject(1, runId);
            try (var result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalStateException("EVALUATION_RCA_NOT_FOUND");
                return json.readTree(result.getString(1));
            }
        }
    }

    private static boolean markdownPresent(Connection connection, UUID runId) throws Exception {
        try (var statement = connection.prepareStatement("""
                SELECT report_markdown IS NOT NULL AND length(report_markdown)>0
                FROM opspilot.rca_report WHERE run_id=? ORDER BY run_version DESC LIMIT 1
                """)) {
            statement.setObject(1, runId);
            try (var result = statement.executeQuery()) { return result.next() && result.getBoolean(1); }
        }
    }

    private static Map<String, EvidenceFact> evidence(Connection connection, UUID runId) throws Exception {
        Map<String, EvidenceFact> values = new LinkedHashMap<>();
        try (var statement = connection.prepareStatement("""
                SELECT e.evidence_id,e.run_id,e.evidence_code,e.observed_at,a.sha256,a.access_level
                FROM opspilot.evidence e JOIN opspilot.artifact a ON a.artifact_id=e.artifact_id
                WHERE e.run_id=?
                """)) {
            statement.setObject(1, runId);
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    values.put(result.getString(1), new EvidenceFact(
                            result.getObject(2, UUID.class), result.getString(3),
                            result.getTimestamp(4).toInstant(), result.getString(5), result.getString(6)));
                }
            }
        }
        return values;
    }

    private static Set<String> strings(Connection connection, String sql, UUID runId) throws Exception {
        Set<String> values = new LinkedHashSet<>();
        try (var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, runId);
            try (var result = statement.executeQuery()) {
                while (result.next()) values.add(result.getString(1));
            }
        }
        return values;
    }

    private static int count(Connection connection, String sql, UUID runId) throws Exception {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, runId);
            try (var result = statement.executeQuery()) { result.next(); return result.getInt(1); }
        }
    }

    private static Usage usage(Connection connection, UUID runId) throws Exception {
        try (var statement = connection.prepareStatement("""
                SELECT COALESCE(sum(mu.input_tokens),0), COALESCE(sum(mu.output_tokens),0),
                       COALESCE(sum(mu.cost_micros),0)
                FROM opspilot.model_call mc
                LEFT JOIN opspilot.model_usage mu ON mu.model_call_id=mc.model_call_id
                WHERE mc.run_id=?
                """)) {
            statement.setObject(1, runId);
            try (var result = statement.executeQuery()) {
                result.next();
                return new Usage(result.getInt(1), result.getInt(2), result.getLong(3));
            }
        }
    }

    private record RunFact(
            String status, Instant startedAt, Instant endedAt,
            Instant executionStartedAt, Instant executionEndedAt) { }
    private record Usage(int inputTokens, int outputTokens, long costMicros) { }
    private record EvidenceFact(
            UUID runId, String evidenceCode, Instant observedAt, String artifactSha256, String accessLevel) { }
}
