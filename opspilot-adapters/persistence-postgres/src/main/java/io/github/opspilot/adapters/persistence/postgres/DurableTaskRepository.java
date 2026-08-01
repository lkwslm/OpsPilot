package io.github.opspilot.adapters.persistence.postgres;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL task leasing with SKIP LOCKED and explicit recovery state. */
public final class DurableTaskRepository {
    private final DataSource dataSource;

    public DurableTaskRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public Optional<LeasedTask> claim(String workerId, Instant now, Duration leaseDuration) {
        return claim(workerId, now, leaseDuration, null);
    }

    public Optional<LeasedTask> claim(
            String workerId, Instant now, Duration leaseDuration, String taskType) {
        if (workerId == null || workerId.isBlank() || leaseDuration.isNegative() || leaseDuration.isZero()) {
            throw new IllegalArgumentException("workerId and positive leaseDuration are required");
        }
        if (taskType != null && taskType.isBlank()) {
            throw new IllegalArgumentException("taskType must be null or non-blank");
        }
        String sql = """
                WITH candidate AS (
                    SELECT task_id FROM opspilot.task
                    WHERE status IN ('PENDING','RECOVERING')
                      AND available_at <= ?
                      AND attempt_count < max_attempts
                      AND (? IS NULL OR task_type = ?)
                    ORDER BY priority DESC, available_at, task_id
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                )
                UPDATE opspilot.task task
                SET status = 'LEASED', lease_owner = ?, lease_until = ?, attempt_count = attempt_count + 1
                FROM candidate
                WHERE task.task_id = candidate.task_id
                RETURNING task.task_id, task.run_id, task.task_type, task.attempt_count,
                          task.max_attempts, task.idempotency_key, task.payload_json::text,
                          task.lease_owner, task.lease_until
                """;
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement(sql)) {
                statement.setTimestamp(1, Timestamp.from(now));
                statement.setString(2, taskType);
                statement.setString(3, taskType);
                statement.setString(4, workerId);
                statement.setTimestamp(5, Timestamp.from(now.plus(leaseDuration)));
                try (var result = statement.executeQuery()) {
                    Optional<LeasedTask> task = result.next()
                            ? Optional.of(read(result)) : Optional.empty();
                    connection.commit();
                    return task;
                }
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("TASK_CLAIM_FAILED", exception);
        }
    }

    public void markRunning(UUID taskId, String workerId) {
        transition(taskId, workerId, "LEASED", "RUNNING", false, false);
    }

    public void complete(UUID taskId, String workerId) {
        transition(taskId, workerId, "RUNNING", "COMPLETED", true, true);
    }

    public void fail(UUID taskId, String workerId) {
        transition(taskId, workerId, "RUNNING", "FAILED", false, true);
    }

    public void retry(UUID taskId, String workerId, Instant availableAt) {
        if (availableAt == null) {
            throw new IllegalArgumentException("availableAt is required");
        }
        String sql = """
                UPDATE opspilot.task
                SET status = 'RECOVERING', lease_owner = NULL, lease_until = NULL, available_at = ?
                WHERE task_id = ? AND status = 'RUNNING' AND lease_owner = ?
                """;
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.from(availableAt));
            statement.setObject(2, taskId);
            statement.setString(3, workerId);
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("TASK_LEASE_STATE_CONFLICT");
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("TASK_RETRY_FAILED", exception);
        }
    }

    public int markExpiredForRecovery(Instant now) {
        return markExpiredForRecovery(now, null);
    }

    public int markExpiredForRecovery(Instant now, String taskType) {
        if (taskType != null && taskType.isBlank()) {
            throw new IllegalArgumentException("taskType must be null or non-blank");
        }
        String sql = """
                UPDATE opspilot.task
                SET status = CASE
                        WHEN attempt_count >= max_attempts THEN 'FAILED'
                        WHEN side_effect_committed_at IS NOT NULL THEN 'COMPLETED'
                        ELSE 'RECOVERING'
                    END,
                    lease_owner = NULL,
                    lease_until = NULL,
                    available_at = ?
                WHERE status IN ('LEASED','RUNNING')
                  AND (lease_until < ? OR lease_owner IS NULL OR lease_until IS NULL)
                  AND (? IS NULL OR task_type = ?)
                """;
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.from(now));
            statement.setTimestamp(2, Timestamp.from(now));
            statement.setString(3, taskType);
            statement.setString(4, taskType);
            return statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("TASK_RECOVERY_FAILED", exception);
        }
    }

    /** Re-enqueues active runs created before the durable PRODUCT_RUN queue existed. */
    public int reconcileMissingProductRunTasks() {
        String sql = """
                INSERT INTO opspilot.task
                    (task_id, run_id, task_type, status, max_attempts, idempotency_key, payload_json)
                SELECT gen_random_uuid(), run.run_id, 'PRODUCT_RUN', 'PENDING', 3,
                       'product-run:' || run.run_id::text,
                       jsonb_build_object('schemaVersion', '1.0.0', 'runId', run.run_id)
                FROM opspilot.incident_run run
                WHERE run.status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')
                  AND NOT EXISTS (
                    SELECT 1 FROM opspilot.task task
                    WHERE task.run_id=run.run_id AND task.task_type='PRODUCT_RUN'
                  )
                ON CONFLICT (run_id, task_type, idempotency_key) DO NOTHING
                """;
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            return statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("PRODUCT_RUN_RECONCILIATION_FAILED", exception);
        }
    }

    private void transition(
            UUID taskId, String workerId, String expectedStatus, String nextStatus,
            boolean sideEffectCommitted, boolean releaseLease) {
        if (taskId == null || workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("taskId and workerId are required");
        }
        String sql = """
                UPDATE opspilot.task
                SET status = ?,
                    lease_owner = CASE WHEN ? THEN NULL ELSE lease_owner END,
                    lease_until = CASE WHEN ? THEN NULL ELSE lease_until END,
                    side_effect_committed_at = CASE WHEN ? THEN now() ELSE side_effect_committed_at END
                WHERE task_id = ? AND status = ? AND lease_owner = ?
                """;
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setString(1, nextStatus);
            statement.setBoolean(2, releaseLease);
            statement.setBoolean(3, releaseLease);
            statement.setBoolean(4, sideEffectCommitted);
            statement.setObject(5, taskId);
            statement.setString(6, expectedStatus);
            statement.setString(7, workerId);
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("TASK_LEASE_STATE_CONFLICT");
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("TASK_TRANSITION_FAILED", exception);
        }
    }

    private static LeasedTask read(java.sql.ResultSet result) throws SQLException {
        return new LeasedTask(
                result.getObject("task_id", UUID.class),
                result.getObject("run_id", UUID.class),
                result.getString("task_type"),
                result.getInt("attempt_count"),
                result.getInt("max_attempts"),
                result.getString("idempotency_key"),
                result.getString("payload_json"),
                result.getString("lease_owner"),
                result.getTimestamp("lease_until").toInstant());
    }

    public record LeasedTask(
            UUID taskId,
            UUID runId,
            String taskType,
            int attempt,
            int maxAttempts,
            String idempotencyKey,
            String payloadJson,
            String leaseOwner,
            Instant leaseUntil) {
    }
}
