package io.github.opspilot.adapters.persistence.postgres.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "artifact", schema = "opspilot")
public class ArtifactEntity {
    @Id
    @Column(name = "artifact_id", nullable = false)
    private UUID id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "task_id")
    private UUID taskId;

    @Column(name = "storage_provider", nullable = false)
    private String storageProvider;

    @Column(name = "object_key", nullable = false)
    private String objectKey;

    @Column(nullable = false)
    private String uri;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "media_type", nullable = false)
    private String mediaType;

    @Column(name = "access_level", nullable = false)
    private String accessLevel;

    @Column(name = "lifecycle_status", nullable = false)
    private String lifecycleStatus;

    @Column(name = "retention_class", nullable = false)
    private String retentionClass;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "metadata_json", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String metadataJson;

    protected ArtifactEntity() {
    }
}
