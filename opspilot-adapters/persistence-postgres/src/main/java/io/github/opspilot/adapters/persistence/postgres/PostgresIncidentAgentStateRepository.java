package io.github.opspilot.adapters.persistence.postgres;

import io.github.opspilot.core.application.state.IncidentAgentStateJson;
import io.github.opspilot.core.application.state.IncidentAgentStatePolicy;
import io.github.opspilot.core.application.state.IncidentAgentStatePolicy.ReferenceRecord;
import io.github.opspilot.core.application.state.IncidentAgentStatePolicy.ReferenceType;
import io.github.opspilot.core.application.state.StateMigrationChain;
import io.github.opspilot.core.domain.identity.DomainIds.RunId;
import io.github.opspilot.core.domain.state.IncidentAgentState;
import io.github.opspilot.core.port.repository.IncidentAgentStateRepository;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL CAS implementation for the Supervisor-owned checkpoint snapshot. */
public final class PostgresIncidentAgentStateRepository implements IncidentAgentStateRepository {
    private final DataSource dataSource;
    private final IncidentAgentStateJson codec;

    public PostgresIncidentAgentStateRepository(DataSource dataSource) {
        this(dataSource, codec(dataSource));
    }

    public PostgresIncidentAgentStateRepository(DataSource dataSource, IncidentAgentStateJson codec) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    @Override
    public Optional<IncidentAgentState> load(RunId runId) {
        Objects.requireNonNull(runId, "runId");
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT state_json::text, version FROM opspilot.agent_state WHERE run_id = ?")) {
            statement.setObject(1, runId.value());
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                IncidentAgentState state = codec.read(result.getString(1).getBytes(StandardCharsets.UTF_8));
                if (state.version() != result.getLong(2)) {
                    throw new StateVersionMismatchException();
                }
                return Optional.of(state);
            }
        } catch (SQLException exception) {
            throw persistenceFailure("STATE_LOAD_FAILED", exception);
        }
    }

    @Override
    public void save(IncidentAgentState state, long expectedVersion) {
        Objects.requireNonNull(state, "state");
        if (state.version() != expectedVersion + 1) {
            throw new IllegalArgumentException("state version must advance expectedVersion exactly once");
        }
        String json = codec.writeString(state);
        try (var connection = dataSource.getConnection()) {
            if (!compareAndSet(connection, state, expectedVersion, json)) {
                throw new StateCasConflictException();
            }
        } catch (SQLException exception) {
            throw persistenceFailure("STATE_SAVE_FAILED", exception);
        }
    }

    static boolean compareAndSet(
            Connection connection, IncidentAgentState state, long expectedVersion, String json) throws SQLException {
        String sql = expectedVersion < 0
                ? """
                  INSERT INTO opspilot.agent_state (run_id, schema_version, state_json, version)
                  VALUES (?, ?, ?::jsonb, ?) ON CONFLICT (run_id) DO NOTHING
                  """
                : """
                  UPDATE opspilot.agent_state
                  SET schema_version = ?, state_json = ?::jsonb, version = ?, updated_at = now()
                  WHERE run_id = ? AND version = ?
                  """;
        try (var statement = connection.prepareStatement(sql)) {
            if (expectedVersion < 0) {
                statement.setObject(1, state.runId().value());
                statement.setString(2, state.schemaVersion());
                statement.setString(3, json);
                statement.setLong(4, state.version());
            } else {
                statement.setString(1, state.schemaVersion());
                statement.setString(2, json);
                statement.setLong(3, state.version());
                statement.setObject(4, state.runId().value());
                statement.setLong(5, expectedVersion);
            }
            return statement.executeUpdate() == 1;
        }
    }

    static IncidentAgentStateJson codec(DataSource dataSource) {
        IncidentAgentStatePolicy.ReferenceCatalog catalog = id -> findReference(dataSource, id);
        return new IncidentAgentStateJson(
                StateMigrationChain.defaults(),
                new IncidentAgentStatePolicy(IncidentAgentStatePolicy.SnapshotLimits.defaults(), catalog));
    }

    private static Optional<ReferenceRecord> findReference(DataSource dataSource, UUID id) {
        String sql = """
                SELECT run_id, reference_type FROM (
                    SELECT run_id, 'EVIDENCE' AS reference_type FROM opspilot.evidence WHERE evidence_id = ?
                    UNION ALL SELECT run_id, 'HYPOTHESIS' FROM opspilot.hypothesis WHERE hypothesis_id = ?
                    UNION ALL SELECT run_id, 'ARTIFACT' FROM opspilot.artifact WHERE artifact_id = ?
                ) references_for_run
                """;
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            statement.setObject(2, id);
            statement.setObject(3, id);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(new ReferenceRecord(
                        id, new RunId(result.getObject(1, UUID.class)), ReferenceType.valueOf(result.getString(2))));
            }
        } catch (SQLException exception) {
            throw persistenceFailure("STATE_REFERENCE_LOOKUP_FAILED", exception);
        }
    }

    private static IllegalStateException persistenceFailure(String code, SQLException cause) {
        return new IllegalStateException(code, cause);
    }

    public static final class StateCasConflictException extends RuntimeException {
        public StateCasConflictException() {
            super("STATE_CAS_CONFLICT");
        }
    }

    public static final class StateVersionMismatchException extends RuntimeException {
        public StateVersionMismatchException() {
            super("STATE_VERSION_MISMATCH");
        }
    }
}
