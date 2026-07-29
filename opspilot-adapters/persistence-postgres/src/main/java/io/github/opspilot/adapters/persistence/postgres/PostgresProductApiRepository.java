package io.github.opspilot.adapters.persistence.postgres;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL product boundary with ownership checks and atomic HTTP idempotency. */
public final class PostgresProductApiRepository {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final DataSource dataSource;

    public PostgresProductApiRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public StoredResponse executeIdempotent(String principal, String operation, String key,
            String requestHash, IdempotentWork work) {
        return executeIdempotent(principal, operation, key, requestHash,
                UUID.randomUUID(), UUID.randomUUID(), work);
    }

    public StoredResponse executeIdempotent(String principal, String operation, String key,
            String requestHash, UUID requestId, UUID traceId, IdempotentWork work) {
        Objects.requireNonNull(work, "work");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(traceId, "traceId");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                boolean claimed;
                try (var statement = connection.prepareStatement("""
                        INSERT INTO opspilot.api_idempotency
                            (principal_id, operation_id, idempotency_key, request_hash,
                             processing_status, request_id, trace_id)
                        VALUES (?, ?, ?, ?, 'PROCESSING', ?, ?)
                        ON CONFLICT DO NOTHING
                        """)) {
                    statement.setString(1, principal);
                    statement.setString(2, operation);
                    statement.setString(3, key);
                    statement.setString(4, requestHash);
                    statement.setObject(5, requestId);
                    statement.setObject(6, traceId);
                    claimed = statement.executeUpdate() == 1;
                }
                if (!claimed) {
                    StoredIdempotency stored = lockIdempotency(connection, principal, operation, key);
                    if (!stored.requestHash().equals(requestHash)) {
                        throw new Conflict("IDEMPOTENCY_KEY_REUSED");
                    }
                    if ("COMPLETED".equals(stored.processingStatus())) {
                        connection.commit();
                        return stored.response();
                    }
                    try (var statement = connection.prepareStatement("""
                            UPDATE opspilot.api_idempotency
                            SET processing_status = 'PROCESSING', response_status = NULL,
                                response_headers = NULL, response_body = NULL,
                                request_id = ?, trace_id = ?, updated_at = now()
                            WHERE principal_id = ? AND operation_id = ? AND idempotency_key = ?
                            """)) {
                        statement.setObject(1, requestId);
                        statement.setObject(2, traceId);
                        statement.setString(3, principal);
                        statement.setString(4, operation);
                        statement.setString(5, key);
                        statement.executeUpdate();
                    }
                }
                StoredResponse result = work.apply(connection);
                Map<String, String> responseHeaders = new LinkedHashMap<>(result.headers());
                responseHeaders.put("X-Request-Id", requestId.toString());
                responseHeaders.put("Trace-Id", traceId.toString());
                StoredResponse response = new StoredResponse(result.status(),
                        Map.copyOf(responseHeaders), result.body());
                try (var statement = connection.prepareStatement("""
                        UPDATE opspilot.api_idempotency
                        SET processing_status = 'COMPLETED', response_status = ?,
                            response_headers = ?::jsonb, response_body = ?, updated_at = now()
                        WHERE principal_id = ? AND operation_id = ? AND idempotency_key = ?
                        """)) {
                    statement.setInt(1, response.status());
                    statement.setString(2, json(response.headers()));
                    statement.setBytes(3, response.body());
                    statement.setString(4, principal);
                    statement.setString(5, operation);
                    statement.setString(6, key);
                    statement.executeUpdate();
                }
                connection.commit();
                return response;
            } catch (RuntimeException | SQLException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("PRODUCT_API_DATABASE_FAILED", exception);
        }
    }

    public IncidentSnapshot createIncident(Connection connection, String principal, UUID incidentId,
            String targetSystemId, List<String> resourceIds, String scenarioId, String title,
            String severity, String ticketJson, List<UUID> inputArtifactIds, Instant createdAt) throws SQLException {
        try (var target = connection.prepareStatement("""
                INSERT INTO opspilot.target_system (target_system_id, display_name)
                VALUES (?, ?) ON CONFLICT DO NOTHING
                """)) {
            target.setString(1, targetSystemId);
            target.setString(2, targetSystemId);
            target.executeUpdate();
        }
        try (var statement = connection.prepareStatement("""
                INSERT INTO opspilot.incident
                    (incident_id, target_system_id, status, created_at, principal_id,
                     resource_ids, scenario_id, title, severity, ticket_json, input_artifact_ids)
                VALUES (?, ?, 'OPEN', ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """)) {
            statement.setObject(1, incidentId);
            statement.setString(2, targetSystemId);
            statement.setTimestamp(3, Timestamp.from(createdAt));
            statement.setString(4, principal);
            statement.setArray(5, connection.createArrayOf("text", resourceIds.toArray()));
            statement.setString(6, scenarioId);
            statement.setString(7, title);
            statement.setString(8, severity);
            statement.setString(9, ticketJson);
            statement.setArray(10, connection.createArrayOf("uuid", inputArtifactIds.toArray()));
            statement.executeUpdate();
        }
        return new IncidentSnapshot(incidentId, targetSystemId, resourceIds, title, severity,
                "OPEN", null, createdAt);
    }

    public Optional<IncidentSnapshot> findIncident(String principal, UUID incidentId) {
        String sql = """
                SELECT i.incident_id, i.target_system_id, i.resource_ids, i.title, i.severity,
                       i.status, i.created_at,
                       (SELECT r.run_id FROM opspilot.incident_run r
                        WHERE r.incident_id = i.incident_id
                          AND r.status NOT IN ('COMPLETED','FAILED','CANCELLED')
                        ORDER BY r.started_at DESC LIMIT 1)
                FROM opspilot.incident i
                WHERE i.incident_id = ? AND i.principal_id = ?
                """;
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, incidentId);
            statement.setString(2, principal);
            try (var result = statement.executeQuery()) {
                return result.next() ? Optional.of(incident(result)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("PRODUCT_API_DATABASE_FAILED", exception);
        }
    }

    public RunSnapshot startRun(Connection connection, String principal, UUID incidentId, UUID runId,
            String modelConfigVersion, String evaluationProfile, Long tokenBudget,
            int deadlineSeconds) throws SQLException {
        requireIncidentOwner(connection, principal, incidentId);
        try (var statement = connection.prepareStatement("""
                INSERT INTO opspilot.incident_run
                    (run_id, incident_id, status, model_configuration_version,
                     evaluation_profile, token_budget, deadline_seconds,
                     effective_model_configuration_json,
                     effective_knowledge_configuration_json,
                     effective_agent_profile_configuration_json)
                VALUES (?, ?, 'QUEUED', ?, ?, ?, ?,
                        jsonb_build_object('schemaVersion','1.0.0','configurationVersion',?),
                        '{"schemaVersion":"1.0.0"}'::jsonb,
                        '{"schemaVersion":"1.0.0"}'::jsonb)
                """)) {
            statement.setObject(1, runId);
            statement.setObject(2, incidentId);
            statement.setString(3, modelConfigVersion);
            statement.setString(4, evaluationProfile);
            if (tokenBudget == null) statement.setNull(5, java.sql.Types.BIGINT);
            else statement.setLong(5, tokenBudget);
            statement.setInt(6, deadlineSeconds);
            statement.setString(7, modelConfigVersion);
            statement.executeUpdate();
        } catch (SQLException exception) {
            if ("23505".equals(exception.getSQLState())) {
                throw new Conflict("INCIDENT_ACTIVE_RUN_EXISTS");
            }
            throw exception;
        }
        return new RunSnapshot(incidentId, runId, "QUEUED", null, 0);
    }

    public RunSnapshot resumeRun(Connection connection, String principal, UUID incidentId,
            UUID runId) throws SQLException {
        return transition(connection, principal, incidentId, runId,
                "status = 'WAITING_INPUT'", "PLANNING", "RUN_NOT_WAITING_INPUT");
    }

    public RunSnapshot cancelRun(Connection connection, String principal, UUID incidentId,
            UUID runId) throws SQLException {
        return transition(connection, principal, incidentId, runId,
                "status NOT IN ('COMPLETED','FAILED','CANCELLED','CANCELLING')",
                "CANCELLING", "RUN_NOT_CANCELLABLE");
    }

    public Optional<RunSnapshot> findRun(String principal, UUID incidentId, UUID requestedRunId) {
        String sql = """
                SELECT r.incident_id, r.run_id, r.status, r.outcome, r.run_version
                FROM opspilot.incident_run r JOIN opspilot.incident i ON i.incident_id = r.incident_id
                WHERE r.incident_id = ? AND i.principal_id = ?
                  AND r.run_id = COALESCE(?, (SELECT r2.run_id FROM opspilot.incident_run r2
                      WHERE r2.incident_id = r.incident_id ORDER BY r2.started_at DESC LIMIT 1))
                """;
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, incidentId);
            statement.setString(2, principal);
            statement.setObject(3, requestedRunId);
            try (var result = statement.executeQuery()) {
                return result.next() ? Optional.of(run(result)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("PRODUCT_API_DATABASE_FAILED", exception);
        }
    }

    public ReportSnapshot report(String principal, UUID incidentId, UUID runId) {
        requireOwnedRun(principal, incidentId, runId);
        String sql = """
                SELECT rr.report_json::text, rr.report_markdown
                FROM opspilot.rca_report rr WHERE rr.run_id = ?
                """;
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, runId);
            try (var result = statement.executeQuery()) {
                if (!result.next() || result.getString(1) == null || result.getString(2) == null) {
                    throw new ReportNotReady();
                }
                return new ReportSnapshot(result.getString(1), result.getString(2));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("PRODUCT_API_DATABASE_FAILED", exception);
        }
    }

    public List<ToolCallSnapshot> toolCalls(String principal, UUID incidentId, UUID runId, int limit) {
        requireOwnedRun(principal, incidentId, runId);
        String sql = """
                SELECT tool_call_id, run_id, tool_name, outcome_code, created_at
                FROM opspilot.tool_call WHERE run_id = ? ORDER BY created_at, tool_call_id LIMIT ?
                """;
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, runId);
            statement.setInt(2, limit);
            List<ToolCallSnapshot> calls = new ArrayList<>();
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    String outcome = result.getString(4);
                    calls.add(new ToolCallSnapshot(result.getObject(1, UUID.class),
                            result.getObject(2, UUID.class), result.getString(3),
                            toolStatus(outcome), failed(outcome) ? outcome : null,
                            result.getTimestamp(5).toInstant(), result.getTimestamp(5).toInstant()));
                }
            }
            return List.copyOf(calls);
        } catch (SQLException exception) {
            throw new IllegalStateException("PRODUCT_API_DATABASE_FAILED", exception);
        }
    }

    public RunSnapshot decideApproval(Connection connection, String principal, UUID incidentId,
            UUID runId, UUID approvalId, String decision, String reason) throws SQLException {
        requireRunOwner(connection, principal, incidentId, runId);
        int changed;
        try (var statement = connection.prepareStatement("""
                UPDATE opspilot.approval SET status = ?, decision_by = ?, decision_reason = ?, decided_at = now()
                WHERE approval_id = ? AND run_id = ? AND status = 'PENDING'
                """)) {
            statement.setString(1, decision);
            statement.setString(2, principal);
            statement.setString(3, reason);
            statement.setObject(4, approvalId);
            statement.setObject(5, runId);
            changed = statement.executeUpdate();
        }
        if (changed != 1) throw new Conflict("APPROVAL_NOT_PENDING");
        String next = "APPROVED".equals(decision) ? "PLANNING" : "FAILED";
        try (var statement = connection.prepareStatement("""
                UPDATE opspilot.incident_run SET status = ?, run_version = run_version + 1, updated_at = now()
                WHERE run_id = ? RETURNING incident_id, run_id, status, outcome, run_version
                """)) {
            statement.setString(1, next);
            statement.setObject(2, runId);
            try (var result = statement.executeQuery()) {
                result.next();
                return run(result);
            }
        }
    }

    public List<EventSnapshot> eventsAfter(String principal, UUID incidentId, UUID runId,
            long lastSequence, int limit) {
        requireOwnedRun(principal, incidentId, runId);
        String sql = """
                SELECT sequence_no, event_type, payload_json::text, committed_at
                FROM opspilot.sse_event
                WHERE run_id = ? AND sequence_no > ?
                ORDER BY sequence_no LIMIT ?
                """;
        try (var connection = dataSource.getConnection()) {
            if (lastSequence > 0) {
                try (var cursor = connection.prepareStatement(
                        "SELECT 1 FROM opspilot.sse_event WHERE run_id = ? AND sequence_no = ?")) {
                    cursor.setObject(1, runId);
                    cursor.setLong(2, lastSequence);
                    try (var found = cursor.executeQuery()) {
                        if (!found.next()) throw new NotFound();
                    }
                }
            }
            try (var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, runId);
            statement.setLong(2, lastSequence);
            statement.setInt(3, limit);
            List<EventSnapshot> events = new ArrayList<>();
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    events.add(new EventSnapshot(result.getLong(1), result.getString(2),
                            result.getString(3), result.getTimestamp(4).toInstant()));
                }
            }
            return List.copyOf(events);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("PRODUCT_API_DATABASE_FAILED", exception);
        }
    }

    private RunSnapshot transition(Connection connection, String principal, UUID incidentId,
            UUID runId, String predicate, String nextState, String conflictCode) throws SQLException {
        requireRunOwner(connection, principal, incidentId, runId);
        String sql = "UPDATE opspilot.incident_run SET status = ?, run_version = run_version + 1, "
                + "updated_at = now() WHERE run_id = ? AND " + predicate
                + " RETURNING incident_id, run_id, status, outcome, run_version";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, nextState);
            statement.setObject(2, runId);
            try (var result = statement.executeQuery()) {
                if (!result.next()) throw new Conflict(conflictCode);
                return run(result);
            }
        }
    }

    private void requireOwnedRun(String principal, UUID incidentId, UUID runId) {
        try (var connection = dataSource.getConnection()) {
            requireRunOwner(connection, principal, incidentId, runId);
        } catch (SQLException exception) {
            throw new IllegalStateException("PRODUCT_API_DATABASE_FAILED", exception);
        }
    }

    private static void requireIncidentOwner(Connection connection, String principal,
            UUID incidentId) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT 1 FROM opspilot.incident WHERE incident_id = ? AND principal_id = ?")) {
            statement.setObject(1, incidentId);
            statement.setString(2, principal);
            try (var result = statement.executeQuery()) {
                if (!result.next()) throw new NotFound();
            }
        }
    }

    private static void requireRunOwner(Connection connection, String principal,
            UUID incidentId, UUID runId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT 1 FROM opspilot.incident_run r
                JOIN opspilot.incident i ON i.incident_id = r.incident_id
                WHERE i.principal_id = ? AND i.incident_id = ? AND r.run_id = ?
                """)) {
            statement.setString(1, principal);
            statement.setObject(2, incidentId);
            statement.setObject(3, runId);
            try (var result = statement.executeQuery()) {
                if (!result.next()) throw new NotFound();
            }
        }
    }

    private static StoredIdempotency lockIdempotency(Connection connection, String principal,
            String operation, String key) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT request_hash, processing_status, response_status,
                       response_headers::text, response_body
                FROM opspilot.api_idempotency
                WHERE principal_id = ? AND operation_id = ? AND idempotency_key = ?
                FOR UPDATE
                """)) {
            statement.setString(1, principal);
            statement.setString(2, operation);
            statement.setString(3, key);
            try (var result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalStateException("IDEMPOTENCY_ROW_MISSING");
                StoredResponse response = "COMPLETED".equals(result.getString(2))
                        ? new StoredResponse(result.getInt(3), headers(result.getString(4)), result.getBytes(5))
                        : null;
                return new StoredIdempotency(result.getString(1), result.getString(2), response);
            }
        }
    }

    private static IncidentSnapshot incident(java.sql.ResultSet result) throws SQLException {
        String[] resources = (String[]) result.getArray(3).getArray();
        return new IncidentSnapshot(result.getObject(1, UUID.class), result.getString(2),
                List.of(resources), result.getString(4), result.getString(5), result.getString(6),
                result.getObject(8, UUID.class), result.getTimestamp(7).toInstant());
    }

    private static RunSnapshot run(java.sql.ResultSet result) throws SQLException {
        return new RunSnapshot(result.getObject(1, UUID.class), result.getObject(2, UUID.class),
                result.getString(3), result.getString(4), result.getLong(5));
    }

    private static String toolStatus(String outcome) {
        if (outcome == null || "STARTED".equals(outcome)) return "STARTED";
        if ("EMPTY".equals(outcome)) return "EMPTY";
        if ("DENIED".equals(outcome)) return "DENIED";
        return failed(outcome) ? "FAILED" : "SUCCEEDED";
    }

    private static boolean failed(String outcome) {
        return outcome != null && (outcome.contains("FAIL") || outcome.contains("ERROR"));
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("JSON_ENCODING_FAILED", exception);
        }
    }

    private static Map<String, String> headers(String value) {
        try {
            return JSON.readValue(value, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("STORED_HEADERS_INVALID", exception);
        }
    }

    @FunctionalInterface
    public interface IdempotentWork {
        StoredResponse apply(Connection connection) throws SQLException;
    }

    public record StoredResponse(int status, Map<String, String> headers, byte[] body) {
        public StoredResponse {
            headers = Map.copyOf(headers);
            body = body.clone();
        }
        public String bodyUtf8() { return new String(body, StandardCharsets.UTF_8); }
    }
    private record StoredIdempotency(String requestHash, String processingStatus, StoredResponse response) { }
    public record IncidentSnapshot(UUID incidentId, String targetSystemId, List<String> resourceIds,
            String title, String severity, String status, UUID activeRunId, Instant createdAt) { }
    public record RunSnapshot(UUID incidentId, UUID runId, String status, String outcome, long version) { }
    public record ReportSnapshot(String json, String markdown) { }
    public record ToolCallSnapshot(UUID toolCallId, UUID runId, String toolName, String status,
            String errorCode, Instant startedAt, Instant endedAt) { }
    public record EventSnapshot(long sequence, String eventType, String payloadJson, Instant committedAt) { }

    public static final class NotFound extends RuntimeException { }
    public static final class ReportNotReady extends RuntimeException { }
    public static final class Conflict extends RuntimeException {
        private final String code;
        public Conflict(String code) { super(code); this.code = code; }
        public String code() { return code; }
    }
}
