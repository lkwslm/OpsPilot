package io.github.opspilot.adapters.persistence.postgres.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "agent_state", schema = "opspilot")
public class AgentStateEntity {
    @Id
    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "schema_version", nullable = false)
    private String schemaVersion;

    @Column(name = "state_json", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String stateJson;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AgentStateEntity() {
    }

    public AgentStateEntity(
            UUID runId, String schemaVersion, String stateJson, long version, Instant updatedAt) {
        this.runId = runId;
        this.schemaVersion = schemaVersion;
        this.stateJson = stateJson;
        this.version = version;
        this.updatedAt = updatedAt;
    }

    public UUID runId() { return runId; }
    public String schemaVersion() { return schemaVersion; }
    public String stateJson() { return stateJson; }
    public long version() { return version; }
    public Instant updatedAt() { return updatedAt; }
}
