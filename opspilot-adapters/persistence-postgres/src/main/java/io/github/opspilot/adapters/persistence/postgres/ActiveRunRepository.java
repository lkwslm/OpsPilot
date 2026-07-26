package io.github.opspilot.adapters.persistence.postgres;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private static final ObjectMapper JSON = new ObjectMapper();

    public void createActiveRun(Connection connection, UUID incidentId, UUID runId) throws SQLException {
        createActiveRun(connection, incidentId, runId, RunConfigurationSnapshot.legacy());
    }

    public void createActiveRun(
            Connection connection,
            UUID incidentId,
            UUID runId,
            RunConfigurationSnapshot configuration) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO opspilot.incident_run (
                    run_id, incident_id, status,
                    model_configuration_version, knowledge_configuration_version,
                    effective_model_configuration_json, effective_knowledge_configuration_json)
                VALUES (?, ?, 'CREATED', ?, ?, ?::jsonb, ?::jsonb)
                """)) {
            statement.setObject(1, runId);
            statement.setObject(2, incidentId);
            statement.setString(3, configuration.modelConfigurationVersion());
            statement.setString(4, configuration.knowledgeConfigurationVersion());
            statement.setString(5, configuration.effectiveModelConfiguration().toString());
            statement.setString(6, configuration.effectiveKnowledgeConfiguration().toString());
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

    public RunConfigurationSnapshot findConfigurationSnapshot(Connection connection, UUID runId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT model_configuration_version, knowledge_configuration_version,
                       effective_model_configuration_json, effective_knowledge_configuration_json
                FROM opspilot.incident_run
                WHERE run_id = ?
                """)) {
            statement.setObject(1, runId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Incident run not found: " + runId);
                }
                return new RunConfigurationSnapshot(
                        result.getString(1),
                        result.getString(2),
                        parseJson(result.getString(3)),
                        parseJson(result.getString(4)));
            }
        }
    }

    private static com.fasterxml.jackson.databind.JsonNode parseJson(String value) throws SQLException {
        try {
            return JSON.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new SQLException("Stored run configuration is not valid JSON", exception);
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
