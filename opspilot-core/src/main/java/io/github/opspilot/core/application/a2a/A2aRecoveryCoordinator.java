package io.github.opspilot.core.application.a2a;

import io.github.opspilot.core.domain.state.StateMachines;
import io.github.opspilot.core.domain.state.StateMachines.A2aTaskState;
import io.github.opspilot.core.domain.state.StateMachines.IncidentRunState;
import io.github.opspilot.core.port.repository.A2aAttemptRepository;
import io.github.opspilot.core.port.repository.A2aAttemptRepository.AttemptDraft;
import io.github.opspilot.core.port.repository.A2aAttemptRepository.AttemptRecord;
import io.github.opspilot.core.port.repository.A2aAttemptRepository.RemoteBinding;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Coordinates write-before-network dispatch, continuation, reconciliation, and cancellation. */
public final class A2aRecoveryCoordinator {

    private final A2aAttemptRepository attempts;
    private final RemoteA2aGateway remote;

    public A2aRecoveryCoordinator(A2aAttemptRepository attempts, RemoteA2aGateway remote) {
        this.attempts = Objects.requireNonNull(attempts, "attempts");
        this.remote = Objects.requireNonNull(remote, "remote");
    }

    public AttemptRecord dispatch(AttemptDraft draft) {
        AttemptRecord prepared = attempts.create(draft);
        RemoteTaskSnapshot response = remote.send(prepared);
        return attempts.bind(prepared.attemptId(), prepared.version(), new RemoteBinding(
                prepared.remoteAgentId(), response.taskId(), response.state(),
                response.artifactCursor()));
    }

    public AttemptRecord retry(AttemptRecord prior, AttemptDraft next) {
        if (!prior.terminal()
                || prior.status() == StateMachines.StepAttemptState.COMPLETED
                || next.attempt() != prior.attempt() + 1
                || !next.runId().equals(prior.runId())
                || !next.stepId().equals(prior.stepId())
                || next.messageId().equals(prior.messageId())
                || next.sessionId().equals(prior.sessionId())) {
            throw new IllegalStateException("A2A_RETRY_IDENTITY_INVALID");
        }
        return dispatch(next);
    }

    public AttemptRecord continueAttempt(AttemptRecord attempt) {
        requireBound(attempt);
        return reconcile(attempt);
    }

    public AttemptRecord reconcile(AttemptRecord attempt) {
        requireBound(attempt);
        AttemptRecord current = observe(attempt, remote.get(
                attempt.remoteAgentId(), attempt.remoteTaskId()));
        if (current.remoteTaskState() != null && !terminal(current.remoteTaskState())) {
            for (RemoteTaskSnapshot update : remote.subscribe(
                    current.remoteAgentId(), current.remoteTaskId(), current.artifactCursor())) {
                current = observe(current, update);
            }
        }
        return current;
    }

    public AttemptRecord cancel(AttemptRecord attempt, Instant requestedAt) {
        AttemptRecord requested = attempt.cancelRequestedAt() == null
                ? attempts.requestCancel(attempt.attemptId(), attempt.version(), requestedAt)
                : attempt;
        if (requested.terminal()) {
            return requested;
        }
        requireBound(requested);
        RemoteTaskSnapshot response = remote.cancel(
                requested.remoteAgentId(), requested.remoteTaskId());
        AttemptRecord observed = observe(requested, response);
        return attempts.markCancelPropagated(
                observed.attemptId(), observed.version(), requestedAt);
    }

    public List<AttemptRecord> recover(
            String owner, Instant leaseUntil, int limit, Instant now) {
        return attempts.claimRecoverable(owner, leaseUntil, limit, now).stream()
                .map(this::recoverAttempt)
                .toList();
    }

    public static IncidentRunState convergeTopLevel(A2aTaskState state) {
        return StateMachines.mapTopLevelTerminal(state);
    }

    private AttemptRecord dispatchReplay(AttemptRecord prepared) {
        RemoteTaskSnapshot response = remote.send(prepared);
        return attempts.bind(prepared.attemptId(), prepared.version(), new RemoteBinding(
                prepared.remoteAgentId(), response.taskId(), response.state(),
                response.artifactCursor()));
    }

    private AttemptRecord recoverAttempt(AttemptRecord attempt) {
        if (!attempt.bound()) {
            return dispatchReplay(attempt);
        }
        if (attempt.cancelRequestedAt() != null && attempt.cancelPropagatedAt() == null) {
            return cancel(attempt, attempt.cancelRequestedAt());
        }
        return reconcile(attempt);
    }

    private AttemptRecord observe(AttemptRecord attempt, RemoteTaskSnapshot snapshot) {
        if (!attempt.remoteTaskId().equals(snapshot.taskId())) {
            throw new IllegalStateException("A2A_REMOTE_TASK_ID_MISMATCH");
        }
        return attempts.observe(attempt.attemptId(), attempt.version(), snapshot.taskId(),
                snapshot.state(), snapshot.artifactCursor());
    }

    private static void requireBound(AttemptRecord attempt) {
        if (!attempt.bound()) {
            throw new IllegalStateException("A2A_ATTEMPT_NOT_BOUND");
        }
    }

    private static boolean terminal(A2aTaskState state) {
        return switch (state) {
            case COMPLETED, FAILED, CANCELED, REJECTED -> true;
            default -> false;
        };
    }

    public interface RemoteA2aGateway {
        RemoteTaskSnapshot send(AttemptRecord prepared);

        RemoteTaskSnapshot get(String remoteAgentId, String remoteTaskId);

        List<RemoteTaskSnapshot> subscribe(
                String remoteAgentId, String remoteTaskId, long afterArtifactCursor);

        RemoteTaskSnapshot cancel(String remoteAgentId, String remoteTaskId);
    }

    public record RemoteTaskSnapshot(String taskId, A2aTaskState state, long artifactCursor) {
        public RemoteTaskSnapshot {
            if (taskId == null || taskId.isBlank() || state == null
                    || state == A2aTaskState.UNSPECIFIED || artifactCursor < 0) {
                throw new IllegalArgumentException("A2A_REMOTE_TASK_SNAPSHOT_INVALID");
            }
        }
    }
}
