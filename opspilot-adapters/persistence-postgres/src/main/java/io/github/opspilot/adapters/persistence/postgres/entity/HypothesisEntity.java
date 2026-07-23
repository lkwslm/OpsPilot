package io.github.opspilot.adapters.persistence.postgres.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "hypothesis", schema = "opspilot")
public class HypothesisEntity {
    @Id
    @Column(name = "hypothesis_id", nullable = false)
    private UUID id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(nullable = false)
    private String statement;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false, precision = 5, scale = 4)
    private BigDecimal confidence;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected HypothesisEntity() {
    }

    public HypothesisEntity(
            UUID id,
            UUID runId,
            String statement,
            String status,
            BigDecimal confidence,
            Instant createdAt,
            Instant updatedAt) {
        this.id = id;
        this.runId = runId;
        this.statement = statement;
        this.status = status;
        this.confidence = confidence;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID id() { return id; }
    public UUID runId() { return runId; }
    public String statement() { return statement; }
    public String status() { return status; }
    public BigDecimal confidence() { return confidence; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
}
