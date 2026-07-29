package io.github.opspilot.adapters.persistence.postgres;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opspilot.adapters.persistence.postgres.LocalVolumeArtifactAccessService.AccessContext;
import io.github.opspilot.adapters.persistence.postgres.LocalVolumeArtifactAccessService.AccessLevel;
import io.github.opspilot.adapters.persistence.postgres.LocalVolumeArtifactAccessService.RetentionClass;
import io.github.opspilot.adapters.persistence.postgres.LocalVolumeArtifactAccessService.WriteRequest;
import io.github.opspilot.core.application.correlation.CorrelationContext;
import io.github.opspilot.core.application.correlation.StableErrorSanitizer;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Append-only, ownership-checked correlation graph and controlled log Artifact adapter. */
public final class PostgresCorrelationAuditRepository {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final DataSource dataSource;
    private final LocalVolumeArtifactAccessService artifacts;

    public PostgresCorrelationAuditRepository(
            DataSource dataSource, LocalVolumeArtifactAccessService artifacts) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
    }

    public AuditEvent append(AuditDraft draft, String controlledRawLog) {
        Objects.requireNonNull(draft, "draft");
        CorrelationContext context = draft.context();
        requireOwned(context.principal(), context.incidentId(), context.runId());
        String rawLog = controlledRawLog == null ? draft.summary() : controlledRawLog;
        StableErrorSanitizer.Projection projection = StableErrorSanitizer.project(rawLog);
        UUID logArtifactId = null;
        if (projection.artifactRequired()) {
            requireRun(context);
            UUID artifactId = UUID.randomUUID();
            artifacts.store(new WriteRequest(artifactId, context.runId(), null, "text/plain",
                    AccessLevel.RUN_PRIVATE, RetentionClass.AUDIT,
                    new ByteArrayInputStream(rawLog.getBytes(StandardCharsets.UTF_8))));
            correlateArtifact(artifactId, context);
            logArtifactId = artifactId;
        }
        UUID auditId = UUID.randomUUID();
        String sql = """
                INSERT INTO opspilot.correlation_audit
                    (audit_id,parent_audit_id,principal_id,incident_id,run_id,step_id,
                     request_id,trace_id,a2a_task_id,invocation_id,boundary,
                     action_fingerprint,permission_summary,result_code,error_code,
                     summary,log_artifact_id,occurred_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?, ?,?::jsonb,?,?,?,?,?)
                """;
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, auditId);
            statement.setObject(2, draft.parentAuditId());
            statement.setString(3, context.principal());
            statement.setObject(4, context.incidentId());
            statement.setObject(5, context.runId());
            statement.setObject(6, context.stepId());
            statement.setObject(7, context.requestId());
            statement.setObject(8, context.traceId());
            statement.setString(9, context.a2aTaskId());
            statement.setObject(10, context.invocationId());
            statement.setString(11, draft.boundary());
            statement.setString(12, draft.actionFingerprint());
            statement.setString(13, json(draft.permissionSummary()));
            statement.setString(14, draft.resultCode());
            statement.setString(15, draft.errorCode());
            statement.setString(16, projection.summary());
            statement.setObject(17, logArtifactId);
            statement.setTimestamp(18, Timestamp.from(draft.occurredAt()));
            statement.executeUpdate();
            return new AuditEvent(auditId, draft.parentAuditId(), context, draft.boundary(),
                    draft.actionFingerprint(), draft.permissionSummary(), draft.resultCode(),
                    draft.errorCode(), projection.summary(), logArtifactId, draft.occurredAt());
        } catch (SQLException exception) {
            throw new IllegalStateException("CORRELATION_AUDIT_APPEND_FAILED", exception);
        }
    }

    public List<AuditEvent> findOwnedChain(String principal, UUID incidentId, UUID requestId) {
        String sql = """
                SELECT a.audit_id,a.parent_audit_id,a.principal_id,a.incident_id,a.run_id,a.step_id,
                       a.request_id,a.trace_id,a.a2a_task_id,a.invocation_id,a.boundary,
                       a.action_fingerprint,a.permission_summary::text,a.result_code,a.error_code,
                       a.summary,a.log_artifact_id,a.occurred_at
                FROM opspilot.correlation_audit a
                JOIN opspilot.incident i ON i.incident_id = a.incident_id
                WHERE a.principal_id = ? AND i.principal_id = ?
                  AND a.incident_id = ? AND a.request_id = ?
                  AND (a.run_id IS NULL OR EXISTS (
                      SELECT 1 FROM opspilot.incident_run r
                      WHERE r.run_id = a.run_id AND r.incident_id = a.incident_id))
                ORDER BY a.occurred_at, a.audit_id
                """;
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setString(1, principal);
            statement.setString(2, principal);
            statement.setObject(3, incidentId);
            statement.setObject(4, requestId);
            List<AuditEvent> events = new ArrayList<>();
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    CorrelationContext context = new CorrelationContext(
                            result.getString(3), result.getObject(7, UUID.class),
                            result.getObject(8, UUID.class), result.getObject(4, UUID.class),
                            result.getObject(5, UUID.class), result.getObject(6, UUID.class),
                            result.getString(9), result.getObject(10, UUID.class));
                    events.add(new AuditEvent(result.getObject(1, UUID.class),
                            result.getObject(2, UUID.class), context, result.getString(11),
                            result.getString(12), jsonMap(result.getString(13)), result.getString(14),
                            result.getString(15), result.getString(16),
                            result.getObject(17, UUID.class), result.getTimestamp(18).toInstant()));
                }
            }
            return List.copyOf(events);
        } catch (SQLException exception) {
            throw new IllegalStateException("CORRELATION_AUDIT_QUERY_FAILED", exception);
        }
    }

    public byte[] readOwnedLog(String principal, UUID incidentId, UUID runId, UUID logArtifactId) {
        String sql = """
                SELECT 1 FROM opspilot.correlation_audit a
                JOIN opspilot.incident i ON i.incident_id = a.incident_id
                WHERE a.principal_id = ? AND i.principal_id = ? AND a.incident_id = ?
                  AND a.run_id = ? AND a.log_artifact_id = ?
                """;
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setString(1, principal);
            statement.setString(2, principal);
            statement.setObject(3, incidentId);
            statement.setObject(4, runId);
            statement.setObject(5, logArtifactId);
            try (var result = statement.executeQuery()) {
                if (!result.next()) throw new AuditNotFound();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("CORRELATION_AUDIT_QUERY_FAILED", exception);
        }
        return artifacts.read(logArtifactId, new AccessContext(runId, null, false, false));
    }

    private void requireOwned(String principal, UUID incidentId, UUID runId) {
        if (incidentId == null) throw new IllegalArgumentException("incidentId is required");
        String sql = """
                SELECT 1 FROM opspilot.incident i
                WHERE i.principal_id = ? AND i.incident_id = ?
                  AND (?::uuid IS NULL OR EXISTS (SELECT 1 FROM opspilot.incident_run r
                       WHERE r.run_id = ? AND r.incident_id = i.incident_id))
                """;
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setString(1, principal);
            statement.setObject(2, incidentId);
            statement.setObject(3, runId);
            statement.setObject(4, runId);
            try (var result = statement.executeQuery()) {
                if (!result.next()) throw new AuditNotFound();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("CORRELATION_AUDIT_QUERY_FAILED", exception);
        }
    }

    private void correlateArtifact(UUID artifactId, CorrelationContext context) {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("""
                UPDATE opspilot.artifact SET request_id=?,trace_id=?,step_id=?,a2a_task_id=?,invocation_id=?
                WHERE artifact_id=? AND run_id=?
                """)) {
            statement.setObject(1, context.requestId());
            statement.setObject(2, context.traceId());
            statement.setObject(3, context.stepId());
            statement.setString(4, context.a2aTaskId());
            statement.setObject(5, context.invocationId());
            statement.setObject(6, artifactId);
            statement.setObject(7, context.runId());
            if (statement.executeUpdate() != 1) throw new AuditNotFound();
        } catch (SQLException exception) {
            throw new IllegalStateException("CORRELATION_ARTIFACT_UPDATE_FAILED", exception);
        }
    }

    private static void requireRun(CorrelationContext context) {
        if (context.runId() == null) throw new IllegalArgumentException("runId is required for log Artifact");
    }

    private static String json(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalArgumentException("JSON_FAILED", exception); }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> jsonMap(String value) {
        try { return JSON.readValue(value, Map.class); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("AUDIT_JSON_INVALID", exception); }
    }

    public record AuditDraft(UUID parentAuditId, CorrelationContext context, String boundary,
            String actionFingerprint, Map<String, String> permissionSummary, String resultCode,
            String errorCode, String summary, Instant occurredAt) {
        public AuditDraft {
            Objects.requireNonNull(context, "context");
            permissionSummary = Map.copyOf(permissionSummary);
            Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }

    public record AuditEvent(UUID auditId, UUID parentAuditId, CorrelationContext context,
            String boundary, String actionFingerprint, Map<String, String> permissionSummary,
            String resultCode, String errorCode, String summary, UUID logArtifactId,
            Instant occurredAt) { }

    public static final class AuditNotFound extends RuntimeException { }
}
