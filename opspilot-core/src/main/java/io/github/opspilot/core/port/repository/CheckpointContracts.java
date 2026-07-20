package io.github.opspilot.core.port.repository;

import io.github.opspilot.core.domain.identity.DomainIds.RunId;
import io.github.opspilot.core.domain.state.IncidentAgentState;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Atomic checkpoint and outbox contracts implemented by the persistence adapter. */
public final class CheckpointContracts {
    private CheckpointContracts() {
    }

    public interface CheckpointUnitOfWork {
        void commit(CheckpointCommand command) throws CheckpointConflict;
    }

    public interface CheckpointStateReader {
        Optional<IncidentAgentState> load(RunId runId);
    }

    public record CheckpointCommand(
            UUID checkpointId,
            IncidentAgentState state,
            long expectedVersion,
            List<CallAudit> callAudits,
            List<ReferenceBinding> bindings,
            List<DomainEvent> outboxEvents) {
        public CheckpointCommand {
            callAudits = List.copyOf(callAudits);
            bindings = List.copyOf(bindings);
            outboxEvents = List.copyOf(outboxEvents);
            if (state.version() != expectedVersion + 1) {
                throw new IllegalArgumentException("checkpoint state must advance expectedVersion exactly once");
            }
        }
    }

    public enum CallKind { TOOL, MODEL, A2A }

    public record CallAudit(UUID auditId, RunId runId, CallKind kind, String actionFingerprint,
                            String outcomeCode, Instant occurredAt) {
    }

    public enum BindingType { EVIDENCE, HYPOTHESIS, ARTIFACT }

    public record ReferenceBinding(UUID bindingId, RunId runId, BindingType type, UUID referenceId) {
    }

    /** A committed fact, never an instruction to perform a domain mutation. */
    public record DomainEvent(UUID eventId, RunId runId, String factType, long stateVersion, Instant occurredAt) {
    }

    /** A requested action; deliberately a different type from DomainEvent. */
    public record DomainCommand(UUID commandId, RunId runId, String actionType, Instant requestedAt) {
    }

    public static final class CheckpointConflict extends RuntimeException {
        public CheckpointConflict(String message) { super(message); }
    }
}
