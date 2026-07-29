package io.github.opspilot.core.a2a;

import io.github.opspilot.core.application.a2a.A2aRecoveryCoordinator;
import io.github.opspilot.core.application.a2a.A2aRecoveryCoordinator.RemoteA2aGateway;
import io.github.opspilot.core.application.a2a.A2aRecoveryCoordinator.RemoteTaskSnapshot;
import io.github.opspilot.core.domain.state.StateMachines;
import io.github.opspilot.core.domain.state.StateMachines.A2aTaskState;
import io.github.opspilot.core.domain.state.StateMachines.StepAttemptState;
import io.github.opspilot.core.port.repository.A2aAttemptRepository;
import io.github.opspilot.core.port.repository.A2aAttemptRepository.AttemptConflict;
import io.github.opspilot.core.port.repository.A2aAttemptRepository.AttemptDraft;
import io.github.opspilot.core.port.repository.A2aAttemptRepository.AttemptRecord;
import io.github.opspilot.core.port.repository.A2aAttemptRepository.RemoteBinding;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class A2aRecoveryCoordinatorTest {
    private static final Instant NOW = Instant.parse("2026-07-18T10:00:00Z");

    @Test
    void dispatchPersistsBeforeNetworkAndRetryUsesFreshIdentities() {
        var attempts = new MemoryAttempts();
        var remote = new RecordingRemote(attempts);
        var coordinator = new A2aRecoveryCoordinator(attempts, remote);
        UUID runId = UUID.randomUUID();
        UUID stepId = UUID.randomUUID();
        AttemptDraft firstDraft = draft(runId, stepId, 1, "message-1", "session-1");
        remote.sends.add(new RemoteTaskSnapshot("remote-task-1", A2aTaskState.SUBMITTED, 0));

        AttemptRecord first = coordinator.dispatch(firstDraft);

        assertTrue(remote.sawDurableUnboundAttempt);
        assertEquals("remote-task-1", first.remoteTaskId());
        AttemptRecord failed = attempts.observe(first.attemptId(), first.version(), first.remoteTaskId(),
                A2aTaskState.FAILED, 1);
        AttemptDraft retryDraft = draft(runId, stepId, 2, "message-2", "session-2");
        remote.sends.add(new RemoteTaskSnapshot("remote-task-2", A2aTaskState.SUBMITTED, 0));

        AttemptRecord retry = coordinator.retry(failed, retryDraft);

        assertEquals("remote-task-1", attempts.find(failed.attemptId()).orElseThrow().remoteTaskId());
        assertEquals(StepAttemptState.FAILED,
                attempts.find(failed.attemptId()).orElseThrow().status());
        assertEquals("remote-task-2", retry.remoteTaskId());
        assertThrows(IllegalStateException.class, () -> coordinator.retry(failed,
                draft(runId, stepId, 2, "message-1", "session-2")));
    }

    @Test
    void disconnectReconcilesWithGetThenSubscribeFromPersistedCursor() {
        var attempts = new MemoryAttempts();
        var remote = new RecordingRemote(attempts);
        var coordinator = new A2aRecoveryCoordinator(attempts, remote);
        remote.sends.add(new RemoteTaskSnapshot("remote-task-1", A2aTaskState.SUBMITTED, 2));
        AttemptRecord attempt = coordinator.dispatch(
                draft(UUID.randomUUID(), UUID.randomUUID(), 1, "message-1", "session-1"));
        assertEquals(2, attempt.artifactCursor());
        remote.getResponse = new RemoteTaskSnapshot("remote-task-1", A2aTaskState.WORKING, 3);
        remote.subscription = List.of(
                new RemoteTaskSnapshot("remote-task-1", A2aTaskState.COMPLETED, 4));

        AttemptRecord reconciled = coordinator.reconcile(attempt);

        assertEquals(List.of("get:remote-task-1", "subscribe:remote-task-1:3"), remote.calls);
        assertEquals(4, reconciled.artifactCursor());
        assertEquals(StepAttemptState.VALIDATING_RESULT, reconciled.status());
    }

    @Test
    void cancellationPersistsIntentBeforePropagationAndIsIdempotent() {
        var attempts = new MemoryAttempts();
        var remote = new RecordingRemote(attempts);
        var coordinator = new A2aRecoveryCoordinator(attempts, remote);
        remote.sends.add(new RemoteTaskSnapshot("remote-task-1", A2aTaskState.SUBMITTED, 0));
        AttemptRecord attempt = coordinator.dispatch(
                draft(UUID.randomUUID(), UUID.randomUUID(), 1, "message-1", "session-1"));
        remote.cancelResponse = new RemoteTaskSnapshot("remote-task-1", A2aTaskState.CANCELED, 0);

        remote.failNextCancel = true;
        assertThrows(IllegalStateException.class,
                () -> coordinator.cancel(attempt, NOW.plusSeconds(1)));
        AttemptRecord pending = attempts.find(attempt.attemptId()).orElseThrow();
        assertEquals(StepAttemptState.CANCEL_REQUESTED, pending.status());

        AttemptRecord cancelled = coordinator.recover(
                "supervisor-restart", NOW.plusSeconds(60), 10, NOW.plusSeconds(2)).getFirst();
        AttemptRecord duplicate = coordinator.cancel(cancelled, NOW.plusSeconds(2));

        assertTrue(remote.sawCancelIntent);
        assertEquals(2, remote.cancelCalls);
        assertEquals(StepAttemptState.CANCELLED, duplicate.status());
        assertEquals(NOW.plusSeconds(1), duplicate.cancelRequestedAt());
        assertEquals(NOW.plusSeconds(1), duplicate.cancelPropagatedAt());
    }

    private static AttemptDraft draft(
            UUID runId, UUID stepId, int attempt, String messageId, String sessionId) {
        return new AttemptDraft(UUID.randomUUID(), runId, stepId, attempt, messageId,
                "a".repeat(64), "diagnosis-agent", sessionId, NOW);
    }

    private static final class RecordingRemote implements RemoteA2aGateway {
        private final MemoryAttempts attempts;
        private final ArrayDeque<RemoteTaskSnapshot> sends = new ArrayDeque<>();
        private final List<String> calls = new ArrayList<>();
        private RemoteTaskSnapshot getResponse;
        private List<RemoteTaskSnapshot> subscription = List.of();
        private RemoteTaskSnapshot cancelResponse;
        private boolean sawDurableUnboundAttempt;
        private boolean sawCancelIntent;
        private boolean failNextCancel;
        private int cancelCalls;

        private RecordingRemote(MemoryAttempts attempts) {
            this.attempts = attempts;
        }

        @Override
        public RemoteTaskSnapshot send(AttemptRecord prepared) {
            AttemptRecord durable = attempts.find(prepared.attemptId()).orElseThrow();
            sawDurableUnboundAttempt = !durable.bound();
            return sends.remove();
        }

        @Override
        public RemoteTaskSnapshot get(String remoteAgentId, String remoteTaskId) {
            calls.add("get:" + remoteTaskId);
            return getResponse;
        }

        @Override
        public List<RemoteTaskSnapshot> subscribe(
                String remoteAgentId, String remoteTaskId, long afterArtifactCursor) {
            calls.add("subscribe:" + remoteTaskId + ":" + afterArtifactCursor);
            return subscription;
        }

        @Override
        public RemoteTaskSnapshot cancel(String remoteAgentId, String remoteTaskId) {
            cancelCalls++;
            sawCancelIntent = attempts.records.values().stream()
                    .anyMatch(record -> record.remoteTaskId().equals(remoteTaskId)
                            && record.cancelRequestedAt() != null
                            && record.status() == StepAttemptState.CANCEL_REQUESTED);
            if (failNextCancel) {
                failNextCancel = false;
                throw new IllegalStateException("REMOTE_CANCEL_TIMEOUT");
            }
            return cancelResponse;
        }
    }

    private static final class MemoryAttempts implements A2aAttemptRepository {
        private final Map<UUID, AttemptRecord> records = new LinkedHashMap<>();

        @Override
        public AttemptRecord create(AttemptDraft draft) {
            AttemptRecord record = new AttemptRecord(draft.attemptId(), draft.runId(), draft.stepId(),
                    draft.attempt(), StepAttemptState.DISPATCHING, draft.messageId(), draft.requestHash(),
                    draft.remoteAgentId(), null, null, 0, draft.sessionId(), null, null, 0);
            AttemptRecord existing = records.putIfAbsent(record.attemptId(), record);
            return existing == null ? record : existing;
        }

        @Override
        public AttemptRecord bind(UUID attemptId, long expectedVersion, RemoteBinding binding) {
            AttemptRecord current = current(attemptId, expectedVersion);
            if (current.bound()) {
                throw new AttemptConflict("A2A_ATTEMPT_ALREADY_BOUND");
            }
            return save(copy(current, StateMachines.mapA2aState(binding.taskState()),
                    binding.remoteTaskId(), binding.taskState(), binding.artifactCursor(),
                    current.cancelRequestedAt(), current.cancelPropagatedAt()));
        }

        @Override
        public AttemptRecord observe(UUID attemptId, long expectedVersion, String remoteTaskId,
                A2aTaskState taskState, long artifactCursor) {
            AttemptRecord current = current(attemptId, expectedVersion);
            if (!remoteTaskId.equals(current.remoteTaskId()) || artifactCursor < current.artifactCursor()) {
                throw new AttemptConflict("A2A_REMOTE_OBSERVATION_CONFLICT");
            }
            if (current.remoteTaskState() != taskState
                    && !StateMachines.A2A_TASK.allows(current.remoteTaskState(), taskState)) {
                throw new AttemptConflict("INVALID_STATE_TRANSITION");
            }
            StepAttemptState status = current.status() == StepAttemptState.CANCEL_REQUESTED
                    && !terminal(taskState)
                    ? StepAttemptState.CANCEL_REQUESTED
                    : StateMachines.mapA2aState(taskState);
            return save(copy(current, status, remoteTaskId, taskState, artifactCursor,
                    current.cancelRequestedAt(), current.cancelPropagatedAt()));
        }

        @Override
        public AttemptRecord requestCancel(UUID attemptId, long expectedVersion, Instant requestedAt) {
            AttemptRecord current = current(attemptId, expectedVersion);
            if (current.cancelRequestedAt() != null || current.terminal()) {
                return current;
            }
            return save(copy(current, StepAttemptState.CANCEL_REQUESTED, current.remoteTaskId(),
                    current.remoteTaskState(), current.artifactCursor(), requestedAt,
                    current.cancelPropagatedAt()));
        }

        @Override
        public AttemptRecord markCancelPropagated(
                UUID attemptId, long expectedVersion, Instant propagatedAt) {
            AttemptRecord current = current(attemptId, expectedVersion);
            if (current.cancelRequestedAt() == null) {
                throw new AttemptConflict("A2A_CANCEL_NOT_REQUESTED");
            }
            return save(copy(current, current.status(), current.remoteTaskId(),
                    current.remoteTaskState(), current.artifactCursor(), current.cancelRequestedAt(),
                    propagatedAt));
        }

        @Override
        public Optional<AttemptRecord> find(UUID attemptId) {
            return Optional.ofNullable(records.get(attemptId));
        }

        @Override
        public List<AttemptRecord> claimRecoverable(
                String recoveryOwner, Instant leaseUntil, int limit, Instant now) {
            return records.values().stream().filter(record -> !record.terminal()).limit(limit).toList();
        }

        private AttemptRecord current(UUID attemptId, long expectedVersion) {
            AttemptRecord current = Optional.ofNullable(records.get(attemptId)).orElseThrow();
            if (current.version() != expectedVersion) {
                throw new AttemptConflict("A2A_ATTEMPT_VERSION_CONFLICT");
            }
            return current;
        }

        private AttemptRecord save(AttemptRecord record) {
            records.put(record.attemptId(), record);
            return record;
        }

        private static AttemptRecord copy(AttemptRecord current, StepAttemptState status,
                String remoteTaskId, A2aTaskState remoteTaskState, long artifactCursor,
                Instant cancelRequestedAt, Instant cancelPropagatedAt) {
            return new AttemptRecord(current.attemptId(), current.runId(), current.stepId(),
                    current.attempt(), status, current.messageId(), current.requestHash(),
                    current.remoteAgentId(), remoteTaskId, remoteTaskState, artifactCursor,
                    current.sessionId(), cancelRequestedAt, cancelPropagatedAt,
                    current.version() + 1);
        }

        private static boolean terminal(A2aTaskState state) {
            return switch (state) {
                case COMPLETED, FAILED, CANCELED, REJECTED -> true;
                default -> false;
            };
        }
    }
}
