package io.github.opspilot.a2a.server;

import io.github.opspilot.a2a.contract.A2aArtifact;
import io.github.opspilot.a2a.contract.A2aRequestHash;
import io.github.opspilot.a2a.contract.A2aSendRequest;
import io.github.opspilot.a2a.contract.A2aTask;
import io.github.opspilot.a2a.contract.A2aTaskEvent;
import io.github.opspilot.a2a.contract.A2aTaskState;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Minimal PostgreSQL A2A task and event store with replay after process restart. */
public final class PostgresA2aTaskStore implements AutoCloseable {

    private final String serverAgentId;
    private final String jdbcUrl;
    private final String username;
    private final String password;

    public PostgresA2aTaskStore(String jdbcUrl, String username, String password) {
        this("opspilot-server", jdbcUrl, username, password);
    }

    public PostgresA2aTaskStore(
            String serverAgentId, String jdbcUrl, String username, String password) {
        this.serverAgentId = required("serverAgentId", serverAgentId);
        this.jdbcUrl = required("jdbcUrl", jdbcUrl);
        this.username = Objects.requireNonNull(username, "username");
        this.password = Objects.requireNonNull(password, "password");
    }

    public CreateResult create(A2aSendRequest request) {
        required("messageId", request.messageId());
        required("contextId", request.contextId());
        required("text", request.text());
        A2aTask task = new A2aTask(
                UUID.randomUUID().toString(), request.contextId(), request.messageId(),
                A2aTaskState.SUBMITTED, null, 1L);
        String requestHash = A2aRequestHash.compute(request);
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                if (!insertTask(connection, task, requestHash)) {
                    A2aTask existing = findByMessageId(connection, request.messageId(), requestHash);
                    connection.commit();
                    return new CreateResult(existing, false);
                }
                insertEvent(connection, task);
                connection.commit();
                return new CreateResult(task, true);
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            throw persistenceFailure("create task", exception);
        }
    }

    public long eventCount(String taskId) {
        String sql = "SELECT COUNT(*) FROM opspilot_a2a.task_event WHERE server_agent_id = ? AND task_id = ?";
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, serverAgentId);
            statement.setString(2, required("taskId", taskId));
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        } catch (SQLException exception) {
            throw persistenceFailure("count task events", exception);
        }
    }

    public A2aTask markWorking(String taskId) {
        return transition(taskId, A2aTaskState.WORKING, null);
    }

    public A2aTask complete(String taskId, String result) {
        String payload = "{\"result\":" + io.github.opspilot.a2a.contract.A2aJson.write(result) + "}";
        A2aArtifact artifact = new A2aArtifact(
                UUID.randomUUID().toString(), "application/json", "1.0.0", sha256(payload), payload);
        return transition(taskId, A2aTaskState.COMPLETED, artifact);
    }

    public A2aTask cancel(String taskId) {
        return transition(taskId, A2aTaskState.CANCELED, null);
    }

    public Optional<A2aTask> get(String taskId) {
        String sql = "SELECT task_id, context_id, message_id, state, artifact_id, media_type, "
                + "schema_version, sha256, artifact_payload AS payload, revision FROM opspilot_a2a.task "
                + "WHERE server_agent_id = ? AND task_id = ?";
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, serverAgentId);
            statement.setString(2, required("taskId", taskId));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readTask(result)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw persistenceFailure("get task", exception);
        }
    }

    public List<A2aTaskEvent> eventsAfter(String taskId, long sequence) {
        String sql = "SELECT task_id, context_id, message_id, state, artifact_id, media_type, "
                + "schema_version, sha256, artifact_payload AS payload, revision FROM opspilot_a2a.task_event "
                + "WHERE server_agent_id = ? AND task_id = ? AND revision > ? ORDER BY revision";
        List<A2aTaskEvent> events = new ArrayList<>();
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, serverAgentId);
            statement.setString(2, required("taskId", taskId));
            statement.setLong(3, sequence);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    A2aTask task = readTask(result);
                    events.add(new A2aTaskEvent(task.revision(), task));
                }
            }
            return List.copyOf(events);
        } catch (SQLException exception) {
            throw persistenceFailure("replay task events", exception);
        }
    }

    @Override
    public void close() {
        // Connections are deliberately opened per operation so process restart has no local state.
    }

    private A2aTask transition(String taskId, A2aTaskState next, A2aArtifact artifact) {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                A2aTask current = lock(connection, taskId);
                if (current.state().terminal()) {
                    throw new IllegalStateException("TASK_TERMINAL:" + current.state());
                }
                A2aTask updated = new A2aTask(
                        current.taskId(), current.contextId(), current.messageId(), next,
                        artifact == null ? current.artifact() : artifact, current.revision() + 1);
                updateTask(connection, updated);
                insertEvent(connection, updated);
                connection.commit();
                return updated;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            throw persistenceFailure("transition task", exception);
        }
    }

    private A2aTask lock(Connection connection, String taskId) throws SQLException {
        String sql = "SELECT task_id, context_id, message_id, state, artifact_id, media_type, "
                + "schema_version, sha256, artifact_payload AS payload, revision FROM opspilot_a2a.task "
                + "WHERE server_agent_id = ? AND task_id = ? FOR UPDATE";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, serverAgentId);
            statement.setString(2, required("taskId", taskId));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalArgumentException("TASK_NOT_FOUND:" + taskId);
                }
                return readTask(result);
            }
        }
    }

    private boolean insertTask(Connection connection, A2aTask task, String requestHash)
            throws SQLException {
        String sql = "INSERT INTO opspilot_a2a.task "
                + "(task_id, server_agent_id, context_id, message_id, request_hash, state, payload_json, revision) "
                + "VALUES (?, ?, ?, ?, ?, ?, '{\"schemaVersion\":\"1.0.0\"}'::jsonb, ?) "
                + "ON CONFLICT (server_agent_id, message_id) DO NOTHING";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, task.taskId());
            statement.setString(2, serverAgentId);
            statement.setString(3, task.contextId());
            statement.setString(4, task.messageId());
            statement.setString(5, requestHash);
            statement.setString(6, task.state().name());
            statement.setLong(7, task.revision());
            return statement.executeUpdate() == 1;
        }
    }

    private A2aTask findByMessageId(
            Connection connection, String messageId, String expectedHash) throws SQLException {
        String sql = "SELECT task_id, context_id, message_id, state, artifact_id, media_type, "
                + "schema_version, sha256, artifact_payload AS payload, revision, request_hash "
                + "FROM opspilot_a2a.task WHERE server_agent_id = ? AND message_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, serverAgentId);
            statement.setString(2, messageId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalStateException("IDEMPOTENCY_RECORD_MISSING:" + messageId);
                }
                if (!expectedHash.equals(result.getString("request_hash"))) {
                    throw new IllegalStateException("MESSAGE_ID_HASH_CONFLICT:" + messageId);
                }
                return readTask(result);
            }
        }
    }

    private void updateTask(Connection connection, A2aTask task) throws SQLException {
        String sql = "UPDATE opspilot_a2a.task SET state = ?, artifact_id = ?, media_type = ?, "
                + "schema_version = ?, sha256 = ?, artifact_payload = ?, revision = ?, "
                + "updated_at = CURRENT_TIMESTAMP WHERE server_agent_id = ? AND task_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindMutable(statement, task, 1);
            statement.setString(8, serverAgentId);
            statement.setString(9, task.taskId());
            statement.executeUpdate();
        }
    }

    private void insertEvent(Connection connection, A2aTask task) throws SQLException {
        String sql = "INSERT INTO opspilot_a2a.task_event "
                + "(task_id, server_agent_id, context_id, message_id, state, event_type, payload_json, "
                + "artifact_id, media_type, schema_version, sha256, artifact_payload, revision) "
                + "VALUES (?, ?, ?, ?, ?, ?, '{\"schemaVersion\":\"1.0.0\"}'::jsonb, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, task.taskId());
            statement.setString(2, serverAgentId);
            statement.setString(3, task.contextId());
            statement.setString(4, task.messageId());
            statement.setString(5, task.state().name());
            statement.setString(6, task.state().name());
            bindArtifact(statement, task.artifact(), 7);
            statement.setLong(12, task.revision());
            statement.executeUpdate();
        }
    }

    private static void bindMutable(PreparedStatement statement, A2aTask task, int index)
            throws SQLException {
        statement.setString(index, task.state().name());
        bindArtifact(statement, task.artifact(), index + 1);
        statement.setLong(index + 6, task.revision());
    }

    private static void bindArtifact(PreparedStatement statement, A2aArtifact artifact, int index)
            throws SQLException {
        statement.setString(index, artifact == null ? null : artifact.artifactId());
        statement.setString(index + 1, artifact == null ? null : artifact.mediaType());
        statement.setString(index + 2, artifact == null ? null : artifact.schemaVersion());
        statement.setString(index + 3, artifact == null ? null : artifact.sha256());
        statement.setString(index + 4, artifact == null ? null : artifact.payload());
    }

    private static A2aTask readTask(ResultSet result) throws SQLException {
        String artifactId = result.getString("artifact_id");
        A2aArtifact artifact = artifactId == null ? null : new A2aArtifact(
                artifactId, result.getString("media_type"), result.getString("schema_version"),
                result.getString("sha256"), result.getString("payload"));
        return new A2aTask(
                result.getString("task_id"), result.getString("context_id"),
                result.getString("message_id"), A2aTaskState.valueOf(result.getString("state")),
                artifact, result.getLong("revision"));
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String required(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static IllegalStateException persistenceFailure(String operation, SQLException exception) {
        return new IllegalStateException("Failed to " + operation, exception);
    }

    public record CreateResult(A2aTask task, boolean created) {
    }
}
