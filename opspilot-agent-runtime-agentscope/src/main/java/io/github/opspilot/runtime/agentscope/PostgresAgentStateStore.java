package io.github.opspilot.runtime.agentscope;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** PostgreSQL-backed AgentScope state isolated by server agent, user, and session. */
public final class PostgresAgentStateStore implements AgentStateStore, AutoCloseable {

    private static final String TABLE = "opspilot_agent_state";

    private final String serverAgentId;
    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final ObjectMapper objectMapper;

    public PostgresAgentStateStore(
            String serverAgentId, String jdbcUrl, String username, String password) {
        this(serverAgentId, jdbcUrl, username, password, new ObjectMapper());
    }

    PostgresAgentStateStore(
            String serverAgentId,
            String jdbcUrl,
            String username,
            String password,
            ObjectMapper objectMapper) {
        this.serverAgentId = required("serverAgentId", serverAgentId);
        this.jdbcUrl = required("jdbcUrl", jdbcUrl);
        this.username = Objects.requireNonNull(username, "username");
        this.password = Objects.requireNonNull(password, "password");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        initializeSchema();
    }

    @Override
    public void save(String userId, String sessionId, String key, State state) {
        Objects.requireNonNull(state, "state");
        upsert(userId, sessionId, key, state.getClass().getName(), false, writeJson(state));
    }

    @Override
    public void save(String userId, String sessionId, String key, List<? extends State> states) {
        Objects.requireNonNull(states, "states");
        if (states.isEmpty()) {
            upsert(userId, sessionId, key, "", true, "[]");
            return;
        }
        Class<?> stateType = Objects.requireNonNull(states.getFirst(), "state").getClass();
        if (states.stream().anyMatch(state -> state == null || state.getClass() != stateType)) {
            throw new IllegalArgumentException("State lists must contain one concrete state type");
        }
        upsert(userId, sessionId, key, stateType.getName(), true, writeJson(states));
    }

    @Override
    public <T extends State> Optional<T> get(
            String userId, String sessionId, String key, Class<T> type) {
        StoredState stored = find(userId, sessionId, key).orElse(null);
        if (stored == null) {
            return Optional.empty();
        }
        if (stored.list() || !stored.type().equals(type.getName())) {
            throw new IllegalStateException("Stored state type does not match " + type.getName());
        }
        return Optional.of(readJson(stored.json(), type));
    }

    @Override
    public <T extends State> List<T> getList(
            String userId, String sessionId, String key, Class<T> type) {
        StoredState stored = find(userId, sessionId, key).orElse(null);
        if (stored == null) {
            return List.of();
        }
        if (!stored.list() || (!stored.type().isEmpty() && !stored.type().equals(type.getName()))) {
            throw new IllegalStateException("Stored state list type does not match " + type.getName());
        }
        JavaType listType = objectMapper.getTypeFactory().constructCollectionType(List.class, type);
        return readJson(stored.json(), listType);
    }

    @Override
    public boolean exists(String userId, String sessionId) {
        String sql = "SELECT 1 FROM " + TABLE
                + " WHERE server_agent_id = ? AND user_id = ? AND session_id = ? LIMIT 1";
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, serverAgentId);
            statement.setString(2, required("userId", userId));
            statement.setString(3, required("sessionId", sessionId));
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        } catch (SQLException exception) {
            throw persistenceFailure("check state existence", exception);
        }
    }

    @Override
    public void delete(String userId, String sessionId) {
        executeDelete(
                "DELETE FROM " + TABLE
                        + " WHERE server_agent_id = ? AND user_id = ? AND session_id = ?",
                userId, sessionId, null);
    }

    @Override
    public void delete(String userId, String sessionId, String key) {
        executeDelete(
                "DELETE FROM " + TABLE
                        + " WHERE server_agent_id = ? AND user_id = ? AND session_id = ? AND state_key = ?",
                userId, sessionId, key);
    }

    @Override
    public Set<String> listSessionIds(String userId) {
        String sql = "SELECT DISTINCT session_id FROM " + TABLE
                + " WHERE server_agent_id = ? AND user_id = ? ORDER BY session_id";
        Set<String> sessions = new TreeSet<>();
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, serverAgentId);
            statement.setString(2, required("userId", userId));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    sessions.add(result.getString(1));
                }
            }
            return sessions;
        } catch (SQLException exception) {
            throw persistenceFailure("list sessions", exception);
        }
    }

    @Override
    public void close() {
        // Connections are opened per operation; there is no process-local state to release.
    }

    private void initializeSchema() {
        String sql = """
                CREATE TABLE IF NOT EXISTS opspilot_agent_state (
                    server_agent_id TEXT NOT NULL,
                    user_id TEXT NOT NULL,
                    session_id TEXT NOT NULL,
                    state_key TEXT NOT NULL,
                    state_type TEXT NOT NULL,
                    is_list BOOLEAN NOT NULL,
                    state_json JSONB NOT NULL,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (server_agent_id, user_id, session_id, state_key)
                )
                """;
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException exception) {
            throw persistenceFailure("initialize state schema", exception);
        }
    }

    private void upsert(
            String userId, String sessionId, String key, String stateType, boolean list, String json) {
        String sql = """
                INSERT INTO opspilot_agent_state
                    (server_agent_id, user_id, session_id, state_key, state_type, is_list, state_json)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (server_agent_id, user_id, session_id, state_key)
                DO UPDATE SET state_type = EXCLUDED.state_type,
                              is_list = EXCLUDED.is_list,
                              state_json = EXCLUDED.state_json,
                              updated_at = CURRENT_TIMESTAMP
                """;
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, serverAgentId);
            statement.setString(2, required("userId", userId));
            statement.setString(3, required("sessionId", sessionId));
            statement.setString(4, required("key", key));
            statement.setString(5, stateType);
            statement.setBoolean(6, list);
            statement.setString(7, json);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw persistenceFailure("save state", exception);
        }
    }

    private Optional<StoredState> find(String userId, String sessionId, String key) {
        String sql = "SELECT state_type, is_list, state_json::text FROM " + TABLE
                + " WHERE server_agent_id = ? AND user_id = ? AND session_id = ? AND state_key = ?";
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, serverAgentId);
            statement.setString(2, required("userId", userId));
            statement.setString(3, required("sessionId", sessionId));
            statement.setString(4, required("key", key));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(new StoredState(
                        result.getString(1), result.getBoolean(2), result.getString(3)));
            }
        } catch (SQLException exception) {
            throw persistenceFailure("load state", exception);
        }
    }

    private void executeDelete(String sql, String userId, String sessionId, String key) {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, serverAgentId);
            statement.setString(2, required("userId", userId));
            statement.setString(3, required("sessionId", sessionId));
            if (key != null) {
                statement.setString(4, required("key", key));
            }
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw persistenceFailure("delete state", exception);
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("State is not JSON serializable", exception);
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored state is not valid JSON", exception);
        }
    }

    private <T> T readJson(String json, JavaType type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored state is not valid JSON", exception);
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

    private record StoredState(String type, boolean list, String json) {
    }
}
