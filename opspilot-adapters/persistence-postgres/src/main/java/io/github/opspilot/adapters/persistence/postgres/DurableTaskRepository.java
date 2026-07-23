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
        if (workerId == null || workerId.isBlank() || leaseDuration.isNegative() || leaseDuration.isZero()) {
            throw new IllegalArgumentException("workerId and positive leaseDuration are required");
        }
        String sql = """
                WITH candidate AS (
                    SELECT task_id FROM opspilot.task
                    WHERE status IN ('PENDING','RECOVERING')
                      AND available_at <= ?
                      AND attempt_count < max_attempts
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
                statement.setString(2, workerId);
                statement.setTimestamp(3, Timestamp.from(now.plus(leaseDuration)));
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

    public int markExpiredForRecovery(Instant now) {
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
                WHERE status IN ('LEASED','RUNNING') AND lease_until < ?
                """;
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.from(now));
            statement.setTimestamp(2, Timestamp.from(now));
            return statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("TASK_RECOVERY_FAILED", exception);
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
