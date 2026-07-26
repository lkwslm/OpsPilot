package io.github.opspilot.adapters.persistence.postgres;

import io.github.opspilot.core.port.repository.UsageLedgerRepository;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Objects;
import java.util.UUID;

/** PostgreSQL append-only usage ledger; model call and usage rows commit atomically. */
public final class PostgresUsageLedgerRepository implements UsageLedgerRepository {
    private final DataSource dataSource;

    public PostgresUsageLedgerRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public void append(UUID runId, UUID modelRevisionId, UsageRecord record) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(record, "record");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                insertModelCall(connection, runId, modelRevisionId, record);
                insertUsage(connection, record);
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw new UsageLedgerPersistenceException("USAGE_LEDGER_APPEND_FAILED", exception);
            }
        } catch (SQLException exception) {
            throw new UsageLedgerPersistenceException("USAGE_LEDGER_CONNECTION_FAILED", exception);
        }
    }

    private static void insertModelCall(
            Connection connection, UUID runId, UUID modelRevisionId, UsageRecord record) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO opspilot.model_call
                    (model_call_id, run_id, model_revision_id, outcome_code, created_at)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, record.invocationId());
            statement.setObject(2, runId);
            if (modelRevisionId == null) {
                statement.setNull(3, Types.OTHER);
            } else {
                statement.setObject(3, modelRevisionId);
            }
            statement.setString(4, record.attemptOutcome());
            statement.setTimestamp(5, Timestamp.from(record.recordedAt()));
            statement.executeUpdate();
        }
    }

    private static void insertUsage(Connection connection, UsageRecord record) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO opspilot.model_usage
                    (usage_id, model_call_id, input_tokens, output_tokens, cached_tokens,
                     cost_micros, cost_estimated, price_table_version, usage_source,
                     estimator_version, attempt_outcome, incident_key, task_key, agent_key, recorded_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, UUID.nameUUIDFromBytes(
                    ("usage:" + record.invocationId()).getBytes(StandardCharsets.UTF_8)));
            statement.setObject(2, record.invocationId());
            statement.setLong(3, record.inputTokens());
            statement.setLong(4, record.outputTokens());
            statement.setLong(5, record.cachedTokens());
            if (record.costMicros() == null) {
                statement.setNull(6, Types.NUMERIC);
            } else {
                statement.setLong(6, record.costMicros());
            }
            statement.setBoolean(7, record.costEstimated());
            statement.setString(8, record.priceTableVersion());
            statement.setString(9, record.usageSource());
            statement.setString(10, record.estimatorVersion());
            statement.setString(11, record.attemptOutcome());
            statement.setString(12, record.incidentId());
            statement.setString(13, record.taskId());
            statement.setString(14, record.agentId());
            statement.setTimestamp(15, Timestamp.from(record.recordedAt()));
            statement.executeUpdate();
        }
    }

    public static final class UsageLedgerPersistenceException extends RuntimeException {
        UsageLedgerPersistenceException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
