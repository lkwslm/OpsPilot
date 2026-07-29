package io.github.opspilot.core.port.repository;

import io.github.opspilot.core.domain.state.StateMachines.A2aTaskState;
import io.github.opspilot.core.domain.state.StateMachines.StepAttemptState;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Durable authority for one Supervisor step attempt and its remote A2A binding. */
public interface A2aAttemptRepository {

    AttemptRecord create(AttemptDraft draft);

    AttemptRecord bind(UUID attemptId, long expectedVersion, RemoteBinding binding);

    AttemptRecord observe(
            UUID attemptId,
            long expectedVersion,
            String remoteTaskId,
            A2aTaskState taskState,
            long artifactCursor);

    AttemptRecord requestCancel(UUID attemptId, long expectedVersion, Instant requestedAt);

    AttemptRecord markCancelPropagated(UUID attemptId, long expectedVersion, Instant propagatedAt);

    Optional<AttemptRecord> find(UUID attemptId);

    List<AttemptRecord> claimRecoverable(
            String recoveryOwner, Instant leaseUntil, int limit, Instant now);

    record AttemptDraft(
            UUID attemptId,
            UUID runId,
            UUID stepId,
            int attempt,
            String messageId,
            String requestHash,
            String remoteAgentId,
            String sessionId,
            Instant createdAt) {
        private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

        public AttemptDraft {
            if (attemptId == null || runId == null || stepId == null || attempt < 1
                    || blank(messageId) || !SHA256.matcher(requestHash == null ? "" : requestHash).matches()
                    || blank(remoteAgentId) || blank(sessionId) || createdAt == null) {
                throw new IllegalArgumentException("A2A_ATTEMPT_DRAFT_INVALID");
            }
        }
    }

    record RemoteBinding(
            String remoteAgentId,
            String remoteTaskId,
            A2aTaskState taskState,
            long artifactCursor) {
        public RemoteBinding {
            if (blank(remoteAgentId) || blank(remoteTaskId) || taskState == null
                    || taskState == A2aTaskState.UNSPECIFIED || artifactCursor < 0) {
                throw new IllegalArgumentException("A2A_REMOTE_BINDING_INVALID");
            }
        }

        public RemoteBinding(String remoteAgentId, String remoteTaskId, A2aTaskState taskState) {
            this(remoteAgentId, remoteTaskId, taskState, 0);
        }
    }

    record AttemptRecord(
            UUID attemptId,
            UUID runId,
            UUID stepId,
            int attempt,
            StepAttemptState status,
            String messageId,
            String requestHash,
            String remoteAgentId,
            String remoteTaskId,
            A2aTaskState remoteTaskState,
            long artifactCursor,
            String sessionId,
            Instant cancelRequestedAt,
            Instant cancelPropagatedAt,
            long version) {
        public AttemptRecord {
            if (attemptId == null || runId == null || stepId == null || attempt < 1
                    || status == null || blank(messageId) || blank(requestHash)
                    || blank(remoteAgentId) || blank(sessionId)
                    || artifactCursor < 0 || version < 0
                    || ((remoteTaskId == null) != (remoteTaskState == null))) {
                throw new IllegalArgumentException("A2A_ATTEMPT_RECORD_INVALID");
            }
        }

        public boolean bound() {
            return remoteTaskId != null;
        }

        public boolean terminal() {
            return switch (status) {
                case COMPLETED, FAILED, CANCELLED, REJECTED, SKIPPED -> true;
                default -> false;
            };
        }
    }

    final class AttemptConflict extends RuntimeException {
        public AttemptConflict(String code) {
            super(code);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
