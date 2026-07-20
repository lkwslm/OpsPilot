package io.github.opspilot.core.application.checkpoint;

import io.github.opspilot.core.port.repository.CheckpointContracts.DomainEvent;

import java.util.Objects;
import java.util.UUID;

/** Projects only committed facts and uses eventId for idempotency. */
public final class CommittedEventProjector {
    private final ProjectionReceiptPort receipts;
    private final ProjectionPort projection;

    public CommittedEventProjector(ProjectionReceiptPort receipts, ProjectionPort projection) {
        this.receipts = Objects.requireNonNull(receipts, "receipts");
        this.projection = Objects.requireNonNull(projection, "projection");
    }

    public ProjectionOutcome project(DomainEvent event) {
        if (!receipts.tryStart(event.eventId())) {
            return ProjectionOutcome.DUPLICATE;
        }
        try {
            projection.apply(event);
            receipts.succeeded(event.eventId());
            return ProjectionOutcome.PROJECTED;
        } catch (RuntimeException failure) {
            receipts.failed(event.eventId(), "PROJECTION_FAILED", true);
            return ProjectionOutcome.RETRYABLE_FAILURE;
        }
    }

    public enum ProjectionOutcome { PROJECTED, DUPLICATE, RETRYABLE_FAILURE }

    public interface ProjectionReceiptPort {
        boolean tryStart(UUID eventId);
        void succeeded(UUID eventId);
        void failed(UUID eventId, String errorCode, boolean retryable);
    }

    @FunctionalInterface
    public interface ProjectionPort { void apply(DomainEvent event); }
}
