package io.github.opspilot.adapters.persistence.postgres;

import io.github.opspilot.core.application.checkpoint.CommittedEventProjector.ProjectionReceiptPort;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

/** Durable event-id receipt used to make committed projections idempotent. */
public final class PostgresProjectionReceiptRepository implements ProjectionReceiptPort {
    private final DataSource dataSource;
    private final String projectorName;

    public PostgresProjectionReceiptRepository(DataSource dataSource, String projectorName) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        if (projectorName == null || projectorName.isBlank()) {
            throw new IllegalArgumentException("projectorName must not be blank");
        }
        this.projectorName = projectorName;
    }

    @Override
    public boolean tryStart(UUID eventId) {
        String sql = """
                INSERT INTO opspilot.projection_receipt (event_id, projector_name, status)
                VALUES (?, ?, 'RUNNING')
                ON CONFLICT (event_id, projector_name) DO UPDATE
                SET status = 'RUNNING', error_code = NULL, updated_at = now()
                WHERE opspilot.projection_receipt.status = 'FAILED'
                """;
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, eventId);
            statement.setString(2, projectorName);
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw new IllegalStateException("PROJECTION_RECEIPT_START_FAILED", exception);
        }
    }

    @Override
    public void succeeded(UUID eventId) {
        update(eventId, "SUCCEEDED", null);
    }

    @Override
    public void failed(UUID eventId, String errorCode, boolean retryable) {
        update(eventId, retryable ? "FAILED" : "SUCCEEDED", errorCode);
    }

    private void update(UUID eventId, String status, String errorCode) {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     UPDATE opspilot.projection_receipt
                     SET status = ?, error_code = ?, updated_at = now()
                     WHERE event_id = ? AND projector_name = ? AND status = 'RUNNING'
                     """)) {
            statement.setString(1, status);
            statement.setString(2, errorCode);
            statement.setObject(3, eventId);
            statement.setString(4, projectorName);
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("PROJECTION_RECEIPT_STATE_CONFLICT");
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("PROJECTION_RECEIPT_UPDATE_FAILED", exception);
        }
    }
}
