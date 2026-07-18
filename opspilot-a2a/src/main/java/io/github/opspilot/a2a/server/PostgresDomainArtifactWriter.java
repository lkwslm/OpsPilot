package io.github.opspilot.a2a.server;

import io.github.opspilot.a2a.contract.A2aArtifactValidator;
import io.github.opspilot.a2a.contract.A2aTask;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Set;

/** Domain writer that validates the full A2A artifact boundary before opening a connection. */
public final class PostgresDomainArtifactWriter {

    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final A2aArtifactValidator validator;

    public PostgresDomainArtifactWriter(String jdbcUrl, String username, String password) {
        this.jdbcUrl = Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        this.username = Objects.requireNonNull(username, "username");
        this.password = Objects.requireNonNull(password, "password");
        this.validator = new A2aArtifactValidator();
    }

    public void write(
            A2aTask task,
            String requestedTaskId,
            String callerAgentId,
            String expectedCallerAgentId,
            Set<String> authorizedAgentIds) {
        validator.validate(
                task, requestedTaskId, callerAgentId, expectedCallerAgentId, authorizedAgentIds);
        String sql = "INSERT INTO opspilot.domain_artifact "
                + "(task_id, artifact_id, media_type, schema_version, sha256, payload) "
                + "VALUES (?, ?, ?, ?, ?, ?::jsonb)";
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, task.taskId());
            statement.setString(2, task.artifact().artifactId());
            statement.setString(3, task.artifact().mediaType());
            statement.setString(4, task.artifact().schemaVersion());
            statement.setString(5, task.artifact().sha256());
            statement.setString(6, task.artifact().payload());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("DOMAIN_ARTIFACT_WRITE_FAILED", exception);
        }
    }
}
