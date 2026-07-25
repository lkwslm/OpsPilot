package io.github.opspilot.adapters.persistence.postgres;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.opspilot.core.application.observability.ResourceTopologyService.TopologyRepository;
import io.github.opspilot.core.application.observability.ResourceTopologyService.TopologySnapshot;
import io.github.opspilot.core.port.observability.ObservationContracts.ResourceRef;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL history repository for stable logical Resources and immutable topology snapshots. */
public final class PostgresTopologyRepository implements TopologyRepository {
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());
    private final DataSource dataSource;

    public PostgresTopologyRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public TopologySnapshot save(TopologySnapshot snapshot) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                upsertTarget(connection, snapshot);
                for (ResourceRef resource : snapshot.resources().values()) upsertResource(connection, snapshot, resource);
                try (var statement = connection.prepareStatement("""
                        INSERT INTO opspilot.topology_snapshot
                          (topology_snapshot_id, target_system_id, topology_version,
                           effective_from, effective_to, snapshot_json)
                        VALUES (?, ?, ?, ?, ?, ?::jsonb)
                        """)) {
                    statement.setObject(1, snapshot.snapshotId());
                    statement.setString(2, snapshot.targetSystemId());
                    statement.setString(3, snapshot.version());
                    statement.setTimestamp(4, Timestamp.from(snapshot.effectiveFrom()));
                    statement.setTimestamp(5, snapshot.effectiveTo() == null ? null : Timestamp.from(snapshot.effectiveTo()));
                    statement.setString(6, JSON.writeValueAsString(snapshot));
                    statement.executeUpdate();
                }
                connection.commit();
                return snapshot;
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            }
        } catch (Exception failure) {
            if (failure instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
                throw new IllegalStateException("TOPOLOGY_VERSION_CONFLICT", failure);
            }
            throw new IllegalStateException("TOPOLOGY_PERSISTENCE_FAILED", failure);
        }
    }

    @Override
    public Optional<TopologySnapshot> activeAt(String targetSystemId, Instant at) {
        return find("""
                SELECT snapshot_json FROM opspilot.topology_snapshot
                WHERE target_system_id = ? AND effective_from <= ?
                  AND (effective_to IS NULL OR effective_to > ?)
                ORDER BY effective_from DESC LIMIT 1
                """, targetSystemId, at, at);
    }

    @Override
    public Optional<TopologySnapshot> findVersion(String targetSystemId, String version) {
        return find("""
                SELECT snapshot_json FROM opspilot.topology_snapshot
                WHERE target_system_id = ? AND topology_version = ?
                """, targetSystemId, version);
    }

    private Optional<TopologySnapshot> find(String sql, Object... parameters) {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                Object value = parameters[index];
                if (value instanceof Instant instant) statement.setTimestamp(index + 1, Timestamp.from(instant));
                else statement.setObject(index + 1, value);
            }
            try (var result = statement.executeQuery()) {
                return result.next()
                        ? Optional.of(JSON.readValue(result.getString(1), TopologySnapshot.class))
                        : Optional.empty();
            }
        } catch (Exception failure) {
            throw new IllegalStateException("TOPOLOGY_READ_FAILED", failure);
        }
    }

    private static void upsertTarget(Connection connection, TopologySnapshot snapshot) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO opspilot.target_system(target_system_id, display_name, metadata_json)
                VALUES (?, ?, '{"schemaVersion":"1.0.0"}'::jsonb) ON CONFLICT DO NOTHING
                """)) {
            statement.setString(1, snapshot.targetSystemId());
            statement.setString(2, snapshot.targetSystemId());
            statement.executeUpdate();
        }
    }

    private static void upsertResource(Connection connection, TopologySnapshot snapshot, ResourceRef resource)
            throws Exception {
        UUID id = UUID.nameUUIDFromBytes((snapshot.targetSystemId() + "\n" + resource.resourceId())
                .getBytes(StandardCharsets.UTF_8));
        try (var statement = connection.prepareStatement("""
                INSERT INTO opspilot.resource(resource_id, target_system_id, resource_type, external_key, metadata_json)
                VALUES (?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (target_system_id, resource_type, external_key) DO NOTHING
                """)) {
            statement.setObject(1, id);
            statement.setString(2, snapshot.targetSystemId());
            statement.setString(3, resource.resourceType().name());
            statement.setString(4, resource.resourceId());
            statement.setString(5, JSON.writeValueAsString(java.util.Map.of(
                    "schemaVersion", "1.0.0", "logicalRef", resource)));
            statement.executeUpdate();
        }
    }
}
