package io.github.opspilot.adapters.persistence.postgres;

import io.github.opspilot.core.domain.state.StateMachines;
import io.github.opspilot.core.domain.state.StateMachines.A2aTaskState;
import io.github.opspilot.core.domain.state.StateMachines.StepAttemptState;
import io.github.opspilot.core.port.repository.A2aAttemptRepository;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL CAS repository for Supervisor-owned A2A step attempts. */
public final class PostgresA2aAttemptRepository implements A2aAttemptRepository {

    private final DataSource dataSource;

    public PostgresA2aAttemptRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public AttemptRecord create(AttemptDraft draft) {
        String sql = """
                INSERT INTO opspilot.step_attempt
                    (attempt_id, step_id, attempt_number, status, idempotency_key,
                     request_hash, version, message_id, remote_agent_id, session_id, created_at)
                SELECT ?, step_id, ?, 'DISPATCHING', ?, ?, 0, ?, ?, ?, ?
                FROM opspilot.incident_step
                WHERE step_id = ? AND run_id = ?
                ON CONFLICT (attempt_id) DO NOTHING
                """;
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, draft.attemptId());
            statement.setInt(2, draft.attempt());
            statement.setString(3, draft.messageId());
            statement.setString(4, draft.requestHash());
            statement.setString(5, draft.messageId());
            statement.setString(6, draft.remoteAgentId());
            statement.setString(7, draft.sessionId());
            statement.setTimestamp(8, Timestamp.from(draft.createdAt()));
            statement.setObject(9, draft.stepId());
            statement.setObject(10, draft.runId());
            if (statement.executeUpdate() != 1) {
                Optional<AttemptRecord> existing = find(draft.attemptId());
                if (existing.isPresent()
                        && existing.get().messageId().equals(draft.messageId())
                        && existing.get().requestHash().equals(draft.requestHash())) {
                    return existing.get();
                }
                throw new AttemptConflict("A2A_ATTEMPT_CREATE_CONFLICT");
            }
            return find(draft.attemptId()).orElseThrow();
        } catch (SQLException exception) {
            throw failure("A2A_ATTEMPT_CREATE_FAILED", exception);
        }
    }

    @Override
    public AttemptRecord bind(UUID attemptId, long expectedVersion, RemoteBinding binding) {
        StepAttemptState status = StateMachines.mapA2aState(binding.taskState());
        String sql = """
                UPDATE opspilot.step_attempt
                SET remote_agent_id = ?, remote_task_id = ?, remote_task_state = ?,
                    status = ?, artifact_cursor = ?, version = version + 1, updated_at = now()
                WHERE attempt_id = ? AND version = ? AND remote_task_id IS NULL
                  AND remote_agent_id = ?
                """;
        executeCas(sql, statement -> {
            statement.setString(1, binding.remoteAgentId());
            statement.setString(2, binding.remoteTaskId());
            statement.setString(3, binding.taskState().name());
            statement.setString(4, status.name());
            statement.setLong(5, binding.artifactCursor());
            statement.setObject(6, attemptId);
            statement.setLong(7, expectedVersion);
            statement.setString(8, binding.remoteAgentId());
        }, "A2A_BIND_CAS_CONFLICT");
        return find(attemptId).orElseThrow();
    }

    @Override
    public AttemptRecord observe(
            UUID attemptId,
            long expectedVersion,
            String remoteTaskId,
            A2aTaskState taskState,
            long artifactCursor) {
        AttemptRecord current = find(attemptId)
                .orElseThrow(() -> new AttemptConflict("A2A_ATTEMPT_NOT_FOUND"));
        if (current.version() != expectedVersion || !remoteTaskId.equals(current.remoteTaskId())
                || taskState == A2aTaskState.UNSPECIFIED
                || artifactCursor < current.artifactCursor()) {
            throw new AttemptConflict("A2A_OBSERVE_CAS_OR_SCOPE_CONFLICT");
        }
        if (current.remoteTaskState() != taskState
                && !StateMachines.A2A_TASK.allows(current.remoteTaskState(), taskState)) {
            throw new AttemptConflict("A2A_REMOTE_STATE_TRANSITION_INVALID");
        }
        StepAttemptState status = current.cancelRequestedAt() != null && !terminal(taskState)
                ? StepAttemptState.CANCEL_REQUESTED
                : StateMachines.mapA2aState(taskState);
        String sql = """
                UPDATE opspilot.step_attempt
                SET remote_task_state = ?, status = ?, artifact_cursor = ?,
                    version = version + 1, updated_at = now()
                WHERE attempt_id = ? AND version = ? AND remote_task_id = ?
                """;
        executeCas(sql, statement -> {
            statement.setString(1, taskState.name());
            statement.setString(2, status.name());
            statement.setLong(3, artifactCursor);
            statement.setObject(4, attemptId);
            statement.setLong(5, expectedVersion);
            statement.setString(6, remoteTaskId);
        }, "A2A_OBSERVE_CAS_CONFLICT");
        return find(attemptId).orElseThrow();
    }

    @Override
    public AttemptRecord requestCancel(UUID attemptId, long expectedVersion, Instant requestedAt) {
        AttemptRecord current = find(attemptId)
                .orElseThrow(() -> new AttemptConflict("A2A_ATTEMPT_NOT_FOUND"));
        if (current.cancelRequestedAt() != null) {
            return current;
        }
        if (current.terminal()) {
            return current;
        }
        String sql = """
                UPDATE opspilot.step_attempt
                SET cancel_requested_at = ?, status = 'CANCEL_REQUESTED',
                    version = version + 1, updated_at = now()
                WHERE attempt_id = ? AND version = ?
                """;
        executeCas(sql, statement -> {
            statement.setTimestamp(1, Timestamp.from(requestedAt));
            statement.setObject(2, attemptId);
            statement.setLong(3, expectedVersion);
        }, "A2A_CANCEL_REQUEST_CAS_CONFLICT");
        return find(attemptId).orElseThrow();
    }

    @Override
    public AttemptRecord markCancelPropagated(
            UUID attemptId, long expectedVersion, Instant propagatedAt) {
        AttemptRecord current = find(attemptId)
                .orElseThrow(() -> new AttemptConflict("A2A_ATTEMPT_NOT_FOUND"));
        if (current.cancelPropagatedAt() != null) {
            return current;
        }
        String sql = """
                UPDATE opspilot.step_attempt
                SET cancel_propagated_at = ?, version = version + 1, updated_at = now()
                WHERE attempt_id = ? AND version = ? AND cancel_requested_at IS NOT NULL
                """;
        executeCas(sql, statement -> {
            statement.setTimestamp(1, Timestamp.from(propagatedAt));
            statement.setObject(2, attemptId);
            statement.setLong(3, expectedVersion);
        }, "A2A_CANCEL_PROPAGATION_CAS_CONFLICT");
        return find(attemptId).orElseThrow();
    }

    @Override
    public Optional<AttemptRecord> find(UUID attemptId) {
        String sql = SELECT + " WHERE attempt.attempt_id = ?";
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, attemptId);
            try (var result = statement.executeQuery()) {
                return result.next() ? Optional.of(read(result)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw failure("A2A_ATTEMPT_READ_FAILED", exception);
        }
    }

    @Override
    public List<AttemptRecord> claimRecoverable(
            String recoveryOwner, Instant leaseUntil, int limit, Instant now) {
        if (recoveryOwner == null || recoveryOwner.isBlank() || limit < 1
                || !leaseUntil.isAfter(now)) {
            throw new IllegalArgumentException("A2A_RECOVERY_CLAIM_INVALID");
        }
        String sql = """
                WITH candidates AS (
                    SELECT attempt_id FROM opspilot.step_attempt
                    WHERE status IN ('DISPATCHING','RECONCILING','QUEUED','RUNNING',
                                     'WAITING_INPUT','WAITING_AUTH','VALIDATING_RESULT','CANCEL_REQUESTED')
                      AND (recovery_lease_until IS NULL OR recovery_lease_until < ?)
                    ORDER BY updated_at, attempt_id
                    FOR UPDATE SKIP LOCKED
                    LIMIT ?
                )
                UPDATE opspilot.step_attempt attempt
                SET recovery_owner = ?, recovery_lease_until = ?,
                    status = CASE WHEN status = 'CANCEL_REQUESTED'
                                  THEN status ELSE 'RECONCILING' END,
                    version = version + 1, updated_at = now()
                FROM candidates
                WHERE attempt.attempt_id = candidates.attempt_id
                RETURNING attempt.attempt_id
                """;
        List<UUID> ids = new ArrayList<>();
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.from(now));
            statement.setInt(2, limit);
            statement.setString(3, recoveryOwner);
            statement.setTimestamp(4, Timestamp.from(leaseUntil));
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    ids.add(result.getObject(1, UUID.class));
                }
            }
        } catch (SQLException exception) {
            throw failure("A2A_RECOVERY_CLAIM_FAILED", exception);
        }
        return ids.stream().map(id -> find(id).orElseThrow()).toList();
    }

    private void executeCas(String sql, SqlBinder binder, String conflictCode) {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            if (statement.executeUpdate() != 1) {
                throw new AttemptConflict(conflictCode);
            }
        } catch (SQLException exception) {
            throw failure(conflictCode, exception);
        }
    }

    private static AttemptRecord read(ResultSet result) throws SQLException {
        String remoteState = result.getString("remote_task_state");
        return new AttemptRecord(
                result.getObject("attempt_id", UUID.class),
                result.getObject("run_id", UUID.class),
                result.getObject("step_id", UUID.class),
                result.getInt("attempt_number"),
                StepAttemptState.valueOf(result.getString("status")),
                result.getString("message_id"),
                result.getString("request_hash"),
                result.getString("remote_agent_id"),
                result.getString("remote_task_id"),
                remoteState == null ? null : A2aTaskState.valueOf(remoteState),
                result.getLong("artifact_cursor"),
                result.getString("session_id"),
                instant(result, "cancel_requested_at"),
                instant(result, "cancel_propagated_at"),
                result.getLong("version"));
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static boolean terminal(A2aTaskState state) {
        return switch (state) {
            case COMPLETED, FAILED, CANCELED, REJECTED -> true;
            default -> false;
        };
    }

    private static IllegalStateException failure(String code, SQLException exception) {
        return new IllegalStateException(code, exception);
    }

    @FunctionalInterface
    private interface SqlBinder {
        void bind(java.sql.PreparedStatement statement) throws SQLException;
    }

    private static final String SELECT = """
            SELECT attempt.attempt_id, step.run_id, attempt.step_id,
                   attempt.attempt_number, attempt.status, attempt.message_id,
                   attempt.request_hash, attempt.remote_agent_id, attempt.remote_task_id,
                   attempt.remote_task_state, attempt.artifact_cursor, attempt.session_id,
                   attempt.cancel_requested_at, attempt.cancel_propagated_at, attempt.version
            FROM opspilot.step_attempt attempt
            JOIN opspilot.incident_step step ON step.step_id = attempt.step_id
            """;
}
