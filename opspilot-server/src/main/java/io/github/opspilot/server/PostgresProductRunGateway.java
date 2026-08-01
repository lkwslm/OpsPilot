package io.github.opspilot.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opspilot.adapters.persistence.postgres.DurableTaskRepository.LeasedTask;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** PostgreSQL-backed, idempotent bridge from a product Run lease to the Supervisor workflow. */
final class PostgresProductRunGateway
        implements ProductRunWorker.RunGateway, ProductRunWorker.FailureSink {
    private final DataSource dataSource;
    private final Phase7RunOrchestrator orchestrator;

    PostgresProductRunGateway(DataSource dataSource, Phase7RunOrchestrator orchestrator) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator");
    }

    @Override
    public void execute(LeasedTask task) throws Exception {
        RunInput input = claimRun(task.runId());
        if (input.completed()) return;
        orchestrator.execute(new Phase7RunOrchestrator.RunRequest(
                input.incidentId(), task.runId(), task.taskId(), input.windowStart(),
                input.windowEnd(), input.deadline()));
    }

    @Override
    public void record(UUID runId, String errorCode) {
        String stableCode = errorCode != null && errorCode.matches("[A-Z][A-Z0-9_]{2,63}")
                ? errorCode : "PRODUCT_RUN_EXECUTION_FAILED";
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var failure = connection.prepareStatement("""
                    INSERT INTO opspilot.chain_failure
                        (failure_id, run_id, error_code, retryable, summary)
                    VALUES (?, ?, ?, false, 'Product Run execution failed')
                    """)) {
                failure.setObject(1, UUID.randomUUID());
                failure.setObject(2, runId);
                failure.setString(3, stableCode);
                failure.executeUpdate();
            }
            try (var run = connection.prepareStatement("""
                    UPDATE opspilot.incident_run
                    SET status='FAILED', execution_ended_at=now(), updated_at=now(), run_version=run_version+1
                    WHERE run_id=? AND status NOT IN ('COMPLETED','FAILED','CANCELLED')
                    """)) {
                run.setObject(1, runId);
                run.executeUpdate();
            }
            connection.commit();
        } catch (SQLException failure) {
            throw new IllegalStateException("PRODUCT_RUN_FAILURE_PERSIST_FAILED", failure);
        }
    }

    private RunInput claimRun(UUID runId) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("""
                    SELECT run.incident_id, run.status, run.deadline_seconds, run.execution_started_at,
                           incident.ticket_json #>> '{window,baseline,start}' AS window_start,
                           incident.ticket_json #>> '{window,recovery,end}' AS window_end
                    FROM opspilot.incident_run run
                    JOIN opspilot.incident incident ON incident.incident_id=run.incident_id
                    WHERE run.run_id=?
                    FOR UPDATE OF run
                    """)) {
                statement.setObject(1, runId);
                try (var result = statement.executeQuery()) {
                    if (!result.next()) {
                        throw new ProductRunWorker.ProductRunFailure("PRODUCT_RUN_NOT_FOUND");
                    }
                    String status = result.getString("status");
                    if ("COMPLETED".equals(status)) {
                        connection.commit();
                        return new RunInput(result.getObject("incident_id", UUID.class), Instant.EPOCH,
                                Instant.EPOCH, Instant.EPOCH, true);
                    }
                    if ("GENERATING_REPORT".equals(status)) {
                        recoverSealedCompletion(connection, runId);
                        connection.commit();
                        return new RunInput(result.getObject("incident_id", UUID.class), Instant.EPOCH,
                                Instant.EPOCH, Instant.EPOCH, true);
                    }
                    if (java.util.Set.of("FAILED", "CANCELLED", "CANCELLING", "GENERATING_REPORT")
                            .contains(status)) {
                        throw new ProductRunWorker.ProductRunFailure("PRODUCT_RUN_STATE_CONFLICT");
                    }
                    Instant start = parseWindow(result.getString("window_start"));
                    Instant end = parseWindow(result.getString("window_end"));
                    if (!start.isBefore(end)) {
                        throw new ProductRunWorker.ProductRunFailure("PRODUCT_RUN_WINDOW_INVALID");
                    }
                    if ("QUEUED".equals(status)) {
                        try (var update = connection.prepareStatement("""
                                UPDATE opspilot.incident_run
                                SET status='PLANNING', updated_at=now(), run_version=run_version+1
                                WHERE run_id=? AND status='QUEUED'
                                """)) {
                            update.setObject(1, runId);
                            if (update.executeUpdate() != 1) {
                                throw new ProductRunWorker.ProductRunFailure("PRODUCT_RUN_CLAIM_CONFLICT");
                            }
                        }
                    }
                    Instant deadline = result.getTimestamp("execution_started_at").toInstant()
                            .plusSeconds(result.getInt("deadline_seconds"));
                    if (!Instant.now().isBefore(deadline)) {
                        throw new ProductRunWorker.ProductRunFailure("PRODUCT_RUN_DEADLINE_EXCEEDED");
                    }
                    RunInput input = new RunInput(result.getObject("incident_id", UUID.class),
                            deadline, start, end, false);
                    connection.commit();
                    return input;
                }
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private static void recoverSealedCompletion(Connection connection, UUID runId) throws Exception {
        try (var run = connection.prepareStatement("""
                UPDATE opspilot.incident_run target
                SET status='COMPLETED',
                    outcome=(SELECT report_json->>'outcome' FROM opspilot.rca_report
                             WHERE run_id=target.run_id ORDER BY run_version DESC LIMIT 1),
                    execution_ended_at=now(), updated_at=now(), run_version=run_version+1
                WHERE target.run_id=? AND target.status='GENERATING_REPORT'
                  AND EXISTS (SELECT 1 FROM opspilot.rca_report WHERE run_id=target.run_id)
                """)) {
            run.setObject(1, runId);
            if (run.executeUpdate() != 1) {
                throw new ProductRunWorker.ProductRunFailure("PRODUCT_RUN_REPORT_RECOVERY_FAILED");
            }
        }
        try (var task = connection.prepareStatement("""
                INSERT INTO opspilot.task
                    (task_id,run_id,task_type,status,max_attempts,idempotency_key,payload_json)
                SELECT gen_random_uuid(),run.run_id,'EVALUATION','PENDING',3,
                       'evaluation:' || run.run_id::text,
                       jsonb_build_object('schemaVersion','1.0.0','scenarioId',incident.scenario_id,
                         'datasetRunId',incident.ticket_json->>'datasetRunId',
                         'groundTruthRelativePath',(incident.ticket_json->>'datasetRunId') || '/ground-truth.json')
                FROM opspilot.incident_run run JOIN opspilot.incident incident
                  ON incident.incident_id=run.incident_id
                WHERE run.run_id=? AND incident.scenario_id IS NOT NULL
                  AND jsonb_exists(incident.ticket_json, 'datasetRunId')
                ON CONFLICT (run_id,task_type,idempotency_key) DO NOTHING
                """)) {
            task.setObject(1, runId);
            task.executeUpdate();
        }
    }

    private static Instant parseWindow(String value) throws ProductRunWorker.ProductRunFailure {
        if (value == null || value.isBlank()) {
            throw new ProductRunWorker.ProductRunFailure("PRODUCT_RUN_WINDOW_MISSING");
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException invalid) {
            throw new ProductRunWorker.ProductRunFailure("PRODUCT_RUN_WINDOW_INVALID");
        }
    }

    private record RunInput(
            UUID incidentId, Instant deadline, Instant windowStart, Instant windowEnd,
            boolean completed) { }
}
