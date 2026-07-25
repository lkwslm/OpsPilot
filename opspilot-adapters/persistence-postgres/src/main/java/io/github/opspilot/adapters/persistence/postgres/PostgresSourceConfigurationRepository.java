package io.github.opspilot.adapters.persistence.postgres;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.opspilot.core.application.observability.SourceConfigurationService.SourceConfigurationRepository;
import io.github.opspilot.core.application.observability.SourceConfigurationService.SourceInstance;
import io.github.opspilot.core.port.observability.ObservationContracts;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Controlled Source instance mapper; resolved credentials never enter config_json. */
public final class PostgresSourceConfigurationRepository implements SourceConfigurationRepository {
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());
    private final DataSource dataSource;

    public PostgresSourceConfigurationRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void save(SourceInstance source) {
        try {
            byte[] json = JSON.writeValueAsBytes(source);
            String hash = ObservationContracts.sha256(json).substring("sha256:".length());
            try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("""
                    INSERT INTO opspilot.observability_source
                      (source_id, target_system_id, source_type, config_identity_sha256, config_json)
                    VALUES (?, ?, ?, ?, ?::jsonb)
                    ON CONFLICT (source_id) DO UPDATE SET source_type=EXCLUDED.source_type,
                      config_identity_sha256=EXCLUDED.config_identity_sha256, config_json=EXCLUDED.config_json
                    """)) {
                statement.setObject(1, stableId(source.sourceId()));
                statement.setString(2, source.targetSystemId());
                statement.setString(3, source.sourceKind().name());
                statement.setString(4, hash);
                statement.setString(5, "{\"schemaVersion\":\"1.0.0\",\"source\":"
                        + new String(json, StandardCharsets.UTF_8) + "}");
                statement.executeUpdate();
            }
        } catch (Exception failure) {
            throw new IllegalStateException("SOURCE_CONFIG_PERSISTENCE_FAILED", failure);
        }
    }

    @Override
    public void delete(String sourceId) {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "DELETE FROM opspilot.observability_source WHERE source_id = ?")) {
            statement.setObject(1, stableId(sourceId));
            statement.executeUpdate();
        } catch (Exception failure) {
            throw new IllegalStateException("SOURCE_CONFIG_DELETE_FAILED", failure);
        }
    }

    @Override
    public List<SourceInstance> findAll() {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "SELECT config_json -> 'source' FROM opspilot.observability_source ORDER BY source_id")) {
            List<SourceInstance> sources = new ArrayList<>();
            try (var result = statement.executeQuery()) {
                while (result.next()) sources.add(JSON.readValue(result.getString(1), SourceInstance.class));
            }
            return List.copyOf(sources);
        } catch (Exception failure) {
            throw new IllegalStateException("SOURCE_CONFIG_READ_FAILED", failure);
        }
    }

    private static UUID stableId(String sourceId) {
        return UUID.nameUUIDFromBytes(sourceId.getBytes(StandardCharsets.UTF_8));
    }
}
