package io.github.opspilot.adapters.persistence.postgres.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "incident", schema = "opspilot")
public class IncidentEntity {
    @Id
    @Column(name = "incident_id", nullable = false)
    private UUID id;

    @Column(name = "target_system_id", nullable = false)
    private String targetSystemId;

    @Column(nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected IncidentEntity() {
    }

    public IncidentEntity(UUID id, String targetSystemId, String status, Instant createdAt) {
        this.id = id;
        this.targetSystemId = targetSystemId;
        this.status = status;
        this.createdAt = createdAt;
    }

    public UUID id() { return id; }
    public String targetSystemId() { return targetSystemId; }
    public String status() { return status; }
    public Instant createdAt() { return createdAt; }
}
