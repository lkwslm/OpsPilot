package io.github.opspilot.adapters.persistence.postgres;

import io.github.opspilot.core.application.state.IncidentAgentStateJson;
import io.github.opspilot.core.domain.identity.DomainIds.RunId;
import io.github.opspilot.core.domain.state.IncidentAgentState;
import io.github.opspilot.core.port.repository.CheckpointContracts.CallAudit;
import io.github.opspilot.core.port.repository.CheckpointContracts.A2aBindingWrite;
import io.github.opspilot.core.port.repository.CheckpointContracts.CheckpointCommand;
import io.github.opspilot.core.port.repository.CheckpointContracts.CheckpointConflict;
import io.github.opspilot.core.port.repository.CheckpointContracts.CheckpointStateReader;
import io.github.opspilot.core.port.repository.CheckpointContracts.CheckpointUnitOfWork;
import io.github.opspilot.core.port.repository.CheckpointContracts.CodeSnapshotWrite;
import io.github.opspilot.core.port.repository.CheckpointContracts.DomainEvent;
import io.github.opspilot.core.port.repository.CheckpointContracts.ReferenceBinding;
import io.github.opspilot.core.port.repository.CheckpointContracts.StepAttemptWrite;
import io.github.opspilot.core.port.repository.CheckpointContracts.StepWrite;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Objects;
import java.util.Optional;

/** One JDBC transaction for the complete durable checkpoint write model. */
public final class PostgresCheckpointUnitOfWork implements CheckpointUnitOfWork, CheckpointStateReader {
    private final DataSource dataSource;
    private final IncidentAgentStateJson codec;
    private final FailureInjector failureInjector;

    public PostgresCheckpointUnitOfWork(DataSource dataSource) {
        this(dataSource, FailureInjector.none());
    }

    public PostgresCheckpointUnitOfWork(DataSource dataSource, FailureInjector failureInjector) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.codec = PostgresIncidentAgentStateRepository.codec(dataSource);
        this.failureInjector = Objects.requireNonNull(failureInjector, "failureInjector");
    }

    @Override
    public void commit(CheckpointCommand command) throws CheckpointConflict {
        Objects.requireNonNull(command, "command");
        String stateJson = codec.writeString(command.state());
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (!PostgresIncidentAgentStateRepository.compareAndSet(
                        connection, command.state(), command.expectedVersion(), stateJson)) {
                    throw new CheckpointConflict("STATE_CAS_CONFLICT");
                }
                updateRun(connection, command.state(), command.expectedVersion());
                failureInjector.at(FailurePoint.AFTER_STATE);
                for (StepWrite step : command.steps()) {
                    upsertStep(connection, command.state().runId(), step);
                }
                failureInjector.at(FailurePoint.AFTER_STEP);
                for (CodeSnapshotWrite snapshot : command.codeSnapshots()) {
                    insertCodeSnapshot(connection, command.state().runId(), snapshot);
                }
                failureInjector.at(FailurePoint.AFTER_CODE_SNAPSHOT);
                for (A2aBindingWrite binding : command.a2aBindings()) {
                    insertA2aBinding(connection, command.state().runId(), binding);
                }
                failureInjector.at(FailurePoint.AFTER_A2A_BINDING);
                for (CallAudit audit : command.callAudits()) {
                    insertAudit(connection, audit);
                }
                failureInjector.at(FailurePoint.AFTER_AUDIT);
                for (ReferenceBinding binding : command.bindings()) {
                    insertBinding(connection, binding);
                }
                failureInjector.at(FailurePoint.AFTER_BINDING);
                for (DomainEvent event : command.outboxEvents()) {
                    insertOutbox(connection, event);
                }
                failureInjector.at(FailurePoint.AFTER_OUTBOX);
                connection.commit();
            } catch (RuntimeException | SQLException failure) {
                connection.rollback();
                if (failure instanceof CheckpointConflict conflict) {
                    throw conflict;
                }
                throw new IllegalStateException("CHECKPOINT_PERSISTENCE_FAILED", failure);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("CHECKPOINT_CONNECTION_FAILED", exception);
        }
    }

    private static void upsertStep(Connection connection, RunId runId, StepWrite step) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO opspilot.incident_step
                    (step_id, run_id, step_type, status, ordinal, version)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (step_id) DO UPDATE SET
                    step_type = EXCLUDED.step_type,
                    status = EXCLUDED.status,
                    ordinal = EXCLUDED.ordinal,
                    version = EXCLUDED.version
                WHERE incident_step.run_id = EXCLUDED.run_id
                  AND incident_step.version <= EXCLUDED.version
                """)) {
            statement.setObject(1, step.stepId().value());
            statement.setObject(2, runId.value());
            statement.setString(3, step.stepType());
            statement.setString(4, step.status().name());
            statement.setInt(5, step.ordinal());
            statement.setLong(6, step.version());
            if (statement.executeUpdate() != 1) {
                throw new CheckpointConflict("STEP_VERSION_OR_SCOPE_CONFLICT");
            }
        }
        for (StepAttemptWrite attempt : step.attempts()) {
            upsertAttempt(connection, step, attempt);
        }
    }

    private static void upsertAttempt(Connection connection, StepWrite step, StepAttemptWrite attempt)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO opspilot.step_attempt
                    (attempt_id, step_id, attempt_number, status, remote_task_id,
                     idempotency_key, request_hash, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (attempt_id) DO UPDATE SET
                    status = EXCLUDED.status,
                    remote_task_id = EXCLUDED.remote_task_id,
                    version = EXCLUDED.version
                WHERE step_attempt.step_id = EXCLUDED.step_id
                  AND step_attempt.attempt_number = EXCLUDED.attempt_number
                  AND step_attempt.idempotency_key = EXCLUDED.idempotency_key
                  AND step_attempt.request_hash = EXCLUDED.request_hash
                  AND step_attempt.version <= EXCLUDED.version
                """)) {
            statement.setObject(1, attempt.attemptId());
            statement.setObject(2, step.stepId().value());
            statement.setInt(3, attempt.attempt().value());
            statement.setString(4, attempt.status().name());
            statement.setString(5, attempt.remoteTaskId() == null
                    ? null : attempt.remoteTaskId().a2aTaskId().wire());
            statement.setString(6, attempt.idempotencyKey());
            statement.setString(7, attempt.requestHash());
            statement.setLong(8, attempt.version());
            if (statement.executeUpdate() != 1) {
                throw new CheckpointConflict("ATTEMPT_IDEMPOTENCY_OR_VERSION_CONFLICT");
            }
        }
    }

    private static void insertCodeSnapshot(
            Connection connection, RunId runId, CodeSnapshotWrite snapshot) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO opspilot.code_snapshot
                    (code_snapshot_id, run_id, repository_id, deployment_revision_id,
                     commit_sha, manifest_artifact_id)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (code_snapshot_id) DO UPDATE
                    SET code_snapshot_id = EXCLUDED.code_snapshot_id
                WHERE code_snapshot.run_id = EXCLUDED.run_id
                  AND code_snapshot.repository_id = EXCLUDED.repository_id
                  AND code_snapshot.deployment_revision_id = EXCLUDED.deployment_revision_id
                  AND code_snapshot.commit_sha = EXCLUDED.commit_sha
                  AND code_snapshot.manifest_artifact_id IS NOT DISTINCT FROM EXCLUDED.manifest_artifact_id
                """)) {
            statement.setObject(1, snapshot.codeSnapshotId());
            statement.setObject(2, runId.value());
            statement.setObject(3, snapshot.repositoryId());
            statement.setObject(4, snapshot.deploymentRevisionId());
            statement.setString(5, snapshot.commitSha());
            statement.setObject(6, snapshot.manifestArtifactId() == null
                    ? null : snapshot.manifestArtifactId().value());
            if (statement.executeUpdate() != 1) {
                throw new CheckpointConflict("CODE_SNAPSHOT_IDEMPOTENCY_CONFLICT");
            }
        }
    }

    private static void insertA2aBinding(
            Connection connection, RunId runId, A2aBindingWrite binding) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO opspilot.a2a_binding
                    (binding_id, run_id, step_id, server_agent_id,
                     remote_task_id, message_id, request_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (binding_id) DO UPDATE
                    SET binding_id = EXCLUDED.binding_id
                WHERE a2a_binding.run_id = EXCLUDED.run_id
                  AND a2a_binding.step_id = EXCLUDED.step_id
                  AND a2a_binding.server_agent_id = EXCLUDED.server_agent_id
                  AND a2a_binding.remote_task_id = EXCLUDED.remote_task_id
                  AND a2a_binding.message_id = EXCLUDED.message_id
                  AND a2a_binding.request_hash = EXCLUDED.request_hash
                """)) {
            statement.setObject(1, binding.bindingId());
            statement.setObject(2, runId.value());
            statement.setObject(3, binding.stepId().value());
            statement.setString(4, binding.remoteTaskId().remoteAgentId());
            statement.setString(5, binding.remoteTaskId().a2aTaskId().wire());
            statement.setString(6, binding.messageId());
            statement.setString(7, binding.requestHash());
            if (statement.executeUpdate() != 1) {
                throw new CheckpointConflict("A2A_BINDING_IDEMPOTENCY_CONFLICT");
            }
        }
    }

    @Override
    public Optional<IncidentAgentState> load(RunId runId) {
        return new PostgresIncidentAgentStateRepository(dataSource, codec).load(runId);
    }

    private static void updateRun(Connection connection, IncidentAgentState state, long expectedVersion)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                UPDATE opspilot.incident_run
                SET status = ?, run_version = ?, updated_at = now()
                WHERE run_id = ? AND run_version = ?
                """)) {
            statement.setString(1, state.status().name());
            statement.setLong(2, state.version());
            statement.setObject(3, state.runId().value());
            statement.setLong(4, Math.max(expectedVersion, 0));
            if (statement.executeUpdate() != 1) {
                throw new CheckpointConflict("RUN_VERSION_CONFLICT");
            }
        }
    }

    private static void insertAudit(Connection connection, CallAudit audit) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO opspilot.call_audit
                    (audit_id, run_id, call_kind, action_fingerprint, outcome_code, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, audit.auditId());
            statement.setObject(2, audit.runId().value());
            statement.setString(3, audit.kind().name());
            statement.setString(4, audit.actionFingerprint());
            statement.setString(5, audit.outcomeCode());
            statement.setTimestamp(6, Timestamp.from(audit.occurredAt()));
            statement.executeUpdate();
        }
    }

    private static void insertBinding(Connection connection, ReferenceBinding binding) throws SQLException {
        validateReference(connection, binding);
        try (var statement = connection.prepareStatement("""
                INSERT INTO opspilot.reference_binding (binding_id, run_id, binding_type, reference_id)
                VALUES (?, ?, ?, ?)
                """)) {
            statement.setObject(1, binding.bindingId());
            statement.setObject(2, binding.runId().value());
            statement.setString(3, binding.type().name());
            statement.setObject(4, binding.referenceId());
            statement.executeUpdate();
        }
    }

    private static void validateReference(Connection connection, ReferenceBinding binding) throws SQLException {
        String target = switch (binding.type()) {
            case EVIDENCE -> "opspilot.evidence WHERE evidence_id = ? AND run_id = ?";
            case HYPOTHESIS -> "opspilot.hypothesis WHERE hypothesis_id = ? AND run_id = ?";
            case ARTIFACT -> "opspilot.artifact WHERE artifact_id = ? AND run_id = ?";
        };
        try (var statement = connection.prepareStatement("SELECT 1 FROM " + target)) {
            statement.setObject(1, binding.referenceId());
            statement.setObject(2, binding.runId().value());
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new CheckpointConflict("REFERENCE_NOT_FOUND_OR_WRONG_RUN");
                }
            }
        }
    }

    private static void insertOutbox(Connection connection, DomainEvent event) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO opspilot.outbox_event
                    (event_id, run_id, fact_type, state_version, payload_json, occurred_at)
                VALUES (?, ?, ?, ?, '{"schemaVersion":"1.0.0"}'::jsonb, ?)
                """)) {
            statement.setObject(1, event.eventId());
            statement.setObject(2, event.runId().value());
            statement.setString(3, event.factType());
            statement.setLong(4, event.stateVersion());
            statement.setTimestamp(5, Timestamp.from(event.occurredAt()));
            statement.executeUpdate();
        }
    }

    public enum FailurePoint {
        AFTER_STATE,
        AFTER_STEP,
        AFTER_CODE_SNAPSHOT,
        AFTER_A2A_BINDING,
        AFTER_AUDIT,
        AFTER_BINDING,
        AFTER_OUTBOX
    }

    @FunctionalInterface
    public interface FailureInjector {
        void at(FailurePoint point);

        static FailureInjector none() {
            return ignored -> { };
        }
    }
}
