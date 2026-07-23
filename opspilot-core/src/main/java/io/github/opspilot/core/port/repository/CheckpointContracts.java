package io.github.opspilot.core.port.repository;

import io.github.opspilot.core.domain.identity.DomainIds.RunId;
import io.github.opspilot.core.domain.identity.DomainIds.ArtifactId;
import io.github.opspilot.core.domain.identity.DomainIds.Attempt;
import io.github.opspilot.core.domain.identity.DomainIds.RemoteTaskId;
import io.github.opspilot.core.domain.identity.DomainIds.StepId;
import io.github.opspilot.core.domain.state.IncidentAgentState;
import io.github.opspilot.core.domain.state.StateMachines.StepAttemptState;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Atomic checkpoint and outbox contracts implemented by the persistence adapter. */
public final class CheckpointContracts {
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern COMMIT_SHA = Pattern.compile("^[0-9a-f]{40}$");

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
            List<StepWrite> steps,
            List<A2aBindingWrite> a2aBindings,
            List<CodeSnapshotWrite> codeSnapshots,
            List<CallAudit> callAudits,
            List<ReferenceBinding> bindings,
            List<DomainEvent> outboxEvents) {
        public CheckpointCommand {
            Objects.requireNonNull(checkpointId, "checkpointId");
            Objects.requireNonNull(state, "state");
            steps = List.copyOf(steps);
            a2aBindings = List.copyOf(a2aBindings);
            codeSnapshots = List.copyOf(codeSnapshots);
            callAudits = List.copyOf(callAudits);
            bindings = List.copyOf(bindings);
            outboxEvents = List.copyOf(outboxEvents);
            if (state.version() != expectedVersion + 1) {
                throw new IllegalArgumentException("checkpoint state must advance expectedVersion exactly once");
            }
            RunId runId = state.runId();
            if (callAudits.stream().anyMatch(value -> !runId.equals(value.runId()))
                    || bindings.stream().anyMatch(value -> !runId.equals(value.runId()))
                    || outboxEvents.stream().anyMatch(value -> !runId.equals(value.runId()))) {
                throw new IllegalArgumentException("checkpoint children must belong to the checkpoint run");
            }
        }

        public CheckpointCommand(
                UUID checkpointId,
                IncidentAgentState state,
                long expectedVersion,
                List<CallAudit> callAudits,
                List<ReferenceBinding> bindings,
                List<DomainEvent> outboxEvents) {
            this(checkpointId, state, expectedVersion, List.of(), List.of(), List.of(),
                    callAudits, bindings, outboxEvents);
        }
    }

    public record StepWrite(
            StepId stepId,
            String stepType,
            int ordinal,
            StepAttemptState status,
            long version,
            List<StepAttemptWrite> attempts) {
        public StepWrite {
            Objects.requireNonNull(stepId, "stepId");
            stepType = requiredText(stepType, "stepType");
            Objects.requireNonNull(status, "status");
            if (ordinal < 0 || version < 0) {
                throw new IllegalArgumentException("step ordinal and version must be non-negative");
            }
            attempts = List.copyOf(attempts);
        }
    }

    public record StepAttemptWrite(
            UUID attemptId,
            Attempt attempt,
            StepAttemptState status,
            RemoteTaskId remoteTaskId,
            String idempotencyKey,
            String requestHash,
            long version) {
        public StepAttemptWrite {
            Objects.requireNonNull(attemptId, "attemptId");
            Objects.requireNonNull(attempt, "attempt");
            Objects.requireNonNull(status, "status");
            idempotencyKey = requiredText(idempotencyKey, "idempotencyKey");
            requirePattern(requestHash, SHA256, "requestHash");
            if (version < 0) {
                throw new IllegalArgumentException("attempt version must be non-negative");
            }
        }
    }

    public record A2aBindingWrite(
            UUID bindingId,
            StepId stepId,
            RemoteTaskId remoteTaskId,
            String messageId,
            String requestHash) {
        public A2aBindingWrite {
            Objects.requireNonNull(bindingId, "bindingId");
            Objects.requireNonNull(stepId, "stepId");
            Objects.requireNonNull(remoteTaskId, "remoteTaskId");
            messageId = requiredText(messageId, "messageId");
            requirePattern(requestHash, SHA256, "requestHash");
        }
    }

    public record CodeSnapshotWrite(
            UUID codeSnapshotId,
            UUID repositoryId,
            UUID deploymentRevisionId,
            String commitSha,
            ArtifactId manifestArtifactId) {
        public CodeSnapshotWrite {
            Objects.requireNonNull(codeSnapshotId, "codeSnapshotId");
            Objects.requireNonNull(repositoryId, "repositoryId");
            Objects.requireNonNull(deploymentRevisionId, "deploymentRevisionId");
            requirePattern(commitSha, COMMIT_SHA, "commitSha");
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

    private static String requiredText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static void requirePattern(String value, Pattern pattern, String field) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " has invalid format");
        }
    }
}
