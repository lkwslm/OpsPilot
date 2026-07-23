package io.github.opspilot.adapters.persistence.postgres.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "incident_run", schema = "opspilot")
public class IncidentRunEntity {
    @Id
    @Column(name = "run_id", nullable = false)
    private UUID id;

    @Column(name = "incident_id", nullable = false)
    private UUID incidentId;

    @Column(nullable = false)
    private String status;

    @Version
    @Column(name = "run_version", nullable = false)
    private long version;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "analysis_sealed_at")
    private Instant analysisSealedAt;

    protected IncidentRunEntity() {
    }

    public IncidentRunEntity(UUID id, UUID incidentId, String status, Instant startedAt) {
        this.id = id;
        this.incidentId = incidentId;
        this.status = status;
        this.startedAt = startedAt;
    }

    public UUID id() { return id; }
    public UUID incidentId() { return incidentId; }
    public String status() { return status; }
    public long version() { return version; }
    public Instant startedAt() { return startedAt; }
    public Instant endedAt() { return endedAt; }
    public Instant analysisSealedAt() { return analysisSealedAt; }

    public void changeStatus(String status) {
        this.status = status;
    }
}
