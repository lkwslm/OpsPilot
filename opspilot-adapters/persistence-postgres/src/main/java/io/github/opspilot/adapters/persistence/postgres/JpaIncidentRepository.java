package io.github.opspilot.adapters.persistence.postgres;

import io.github.opspilot.adapters.persistence.postgres.entity.IncidentEntity;
import io.github.opspilot.core.domain.identity.DomainIds.IncidentId;
import jakarta.persistence.EntityManager;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Adapter-local JPA CRUD mapping; callers own the surrounding transaction. */
public final class JpaIncidentRepository {
    private final EntityManager entityManager;

    public JpaIncidentRepository(EntityManager entityManager) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager");
    }

    public IncidentSnapshot save(IncidentSnapshot incident) {
        Objects.requireNonNull(incident, "incident");
        return toDomain(entityManager.merge(new IncidentEntity(
                incident.id().value(), incident.targetSystemId(), incident.status(), incident.createdAt())));
    }

    public Optional<IncidentSnapshot> find(IncidentId id) {
        Objects.requireNonNull(id, "id");
        return Optional.ofNullable(entityManager.find(IncidentEntity.class, id.value()))
                .map(JpaIncidentRepository::toDomain);
    }

    public boolean delete(IncidentId id) {
        Objects.requireNonNull(id, "id");
        IncidentEntity entity = entityManager.find(IncidentEntity.class, id.value());
        if (entity == null) {
            return false;
        }
        entityManager.remove(entity);
        return true;
    }

    private static IncidentSnapshot toDomain(IncidentEntity entity) {
        return new IncidentSnapshot(
                new IncidentId(entity.id()), entity.targetSystemId(), entity.status(), entity.createdAt());
    }

    public record IncidentSnapshot(
            IncidentId id, String targetSystemId, String status, Instant createdAt) {
        public IncidentSnapshot {
            Objects.requireNonNull(id, "id");
            if (targetSystemId == null || targetSystemId.isBlank()) {
                throw new IllegalArgumentException("targetSystemId must not be blank");
            }
            if (status == null || status.isBlank()) {
                throw new IllegalArgumentException("status must not be blank");
            }
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }
}
