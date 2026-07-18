package io.github.opspilot.adapters.persistence.postgres;

import org.postgresql.util.PSQLException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/** Minimal PostgreSQL adapter for the single-active-run invariant. */
public final class ActiveRunRepository {
    private static final String UNIQUE_VIOLATION = "23505";
    private static final String ACTIVE_RUN_CONSTRAINT = "uq_incident_one_active_run";

    public void createActiveRun(Connection connection, UUID incidentId, UUID runId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO opspilot.incident_run (run_id, incident_id, status)
                VALUES (?, ?, 'CREATED')
                """)) {
            statement.setObject(1, runId);
            statement.setObject(2, incidentId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            String constraint = exception instanceof PSQLException postgresException
                    && postgresException.getServerErrorMessage() != null
                    ? postgresException.getServerErrorMessage().getConstraint()
                    : null;
            if (!UNIQUE_VIOLATION.equals(exception.getSQLState())
                    || !ACTIVE_RUN_CONSTRAINT.equals(constraint)) {
                throw exception;
            }
            connection.rollback();
            throw new ActiveRunExistsException(findActiveRun(connection, incidentId), exception);
        }
    }

    private static UUID findActiveRun(Connection connection, UUID incidentId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT run_id
                FROM opspilot.incident_run
                WHERE incident_id = ?
                  AND status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')
                """)) {
            statement.setObject(1, incidentId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Active run constraint failed without an existing active run");
                }
                return result.getObject(1, UUID.class);
            }
        }
    }
}
